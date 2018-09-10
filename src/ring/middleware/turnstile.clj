(ns ring.middleware.turnstile
  (:require [clojure.string :as string]
            [schema.core :as s]
            [schema-tools.core :as st]
            [turnstile.core :refer :all]
            [clojure.tools.logging :as log]))

;;--- Schemas
(s/defschema AnyMap {s/Any s/Any})
(def HttpRequest AnyMap)
(def HttpResponse AnyMap)

(s/defschema Limit
  {:key-fn (s/=> s/Str HttpRequest)
   :rate-limited-fn (s/=> s/Bool HttpRequest)
   :limit s/Int})

(s/defschema RedisConf
  (st/optional-keys
   {:host s/Str
    :port s/Int
    :uri s/Str
    :password s/Str
    :db s/Int}))

(s/defschema Conf
  {:redis-conf RedisConf
   :limits [Limit]
   (s/optional-key :rate-limit-handler) (s/=> HttpResponse HttpRequest s/Int)})

;;--- Limits

(defn ip-limit [n]
  {:key-fn #(:remote-addr %)
   :rate-limited-fn (constantly true)
   :limit n})

(defn header-limit [n header]
  {:key-fn #(get-in % [:headers header])
   :rate-limited-fn #(some? (get-in % [:headers header]))
   :limit n})

;;--- Helpers

(s/defn combine-limits :- Limit
  [limits :- [Limit]
   n :- s/Int]
  {:key-fn (fn [request]
             (->> limits
                  (map (fn [{:keys [key-fn]}]
                         (key-fn request)))
                  (string/join "-")))
   :rate-limited-fn (fn [request]
                      (some->> limits
                               (map (fn [{:keys [rate-limited-fn]}]
                                      (rate-limited-fn request)))
                               (every? true?)))
   :limit n})

(defn make-token []
  (str (java.util.UUID/randomUUID)))

(defn rate-limited?
  [turnstile value]
  (if (has-space? turnstile value)
    false
    (do (expire-entries turnstile (System/currentTimeMillis))
        (not (has-space? turnstile value)))))

(s/defn limit-to-apply :- (s/maybe Limit)
  [request :- HttpRequest
   limits :- [Limit]]
  (some (fn [{:keys [rate-limited-fn] :as limit}]
          (and (rate-limited-fn request)
               limit))
        limits))

(defn next-slot-in-sec
  [next-slot-in-ms]
  (-> next-slot-in-ms
      (/ 1000)
      Math/ceil
      int))

(defn default-rate-limit-handler
  [request next-slot-in-ms]
  (let [retry-after (next-slot-in-sec next-slot-in-ms)]
    {:status 429
     :headers {"Content-Type" "application/json"
               "Retry-After" retry-after}
     :body "{\"error\": \"Too Many Requests\"}"}))

(defn with-rate-limit-headers
  [response limit remaining]
  (update response
          :headers
          #(assoc %
                  "X-RateLimit-Limit" limit
                  "X-RateLimit-Remaining" remaining)))

(def no-limit -1)

(defn unlimited?
  [limit]
  (= limit no-limit))

;;--- Middleware

(defn wrap-rate-limit
  "Middleware for the turnstile rate limiting service"
  [handler
   {:keys [redis-conf limits rate-limit-handler]
    :or {rate-limit-handler default-rate-limit-handler} :as conf}]
  ;; Check the configuration
  (s/validate Conf conf)
  (fn [request]
    (if-let [{:keys [key-fn limit rate-limited-fn]} (limit-to-apply request limits)]
      (let [turnstile
            (map->RedisTurnstile {:conn-spec redis-conf
                                  :pool {}
                                  :name (key-fn request)
                                  :expiration-ms (* 1000 60 60)})]
        (cond
          (or (unlimited? limit)
              (not (rate-limited-fn request))) (handler request)
          (rate-limited? turnstile limit) (rate-limit-handler
                                           request
                                           (next-slot turnstile
                                                      limit
                                                      (System/currentTimeMillis)))
          :else (do
                  (add-timed-item turnstile
                                  (java.util.UUID/randomUUID)
                                  (System/currentTimeMillis))
                  (with-rate-limit-headers
                     (handler request)
                     limit
                     (space turnstile limit)))))
      ;; No limit to apply
      (handler request))))
