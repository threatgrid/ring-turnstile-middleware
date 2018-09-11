(ns ring.middleware.turnstile
  (:require [schema-tools.core :as st]
            [schema.core :as s]
            [turnstile.core :refer :all]))

;;--- Schemas
(s/defschema AnyMap {s/Any s/Any})
(def HttpRequest AnyMap)
(def HttpResponse AnyMap)

(s/defschema Limit
  (st/merge
   {:nb-request-per-hour s/Int
    :rate-limit-key s/Str
    :header-infix s/Str}
   (st/optional-keys
    {:turnstile (s/protocol Turnstile)})))

(s/defschema LimitFunction
  (s/=> Limit HttpRequest))

(s/defschema RedisConf
  (st/optional-keys
   {:host s/Str
    :port s/Int
    :uri s/Str
    :password s/Str
    :db s/Int}))

(s/defschema Conf
  (st/merge
   {:redis-conf RedisConf
    :limit-fns [LimitFunction]}
   (st/optional-keys
    {:key-prefix s/Str
     :rate-limit-handler (s/=> HttpResponse HttpRequest s/Int)})))

;;--- Limits

(s/defn ip-limit [n] :- LimitFunction
  (fn [request]
    {:nb-request-per-hour n
     :rate-limit-key (:remote-addr request)
     :header-infix "IP"}))

(s/defn header-limit :- LimitFunction
  ([n header header-infix]
   (fn [request]
     (when-let [header (get-in request [:headers header])]
       {:nb-request-per-hour n
        :rate-limit-key header
        :header-infix header-infix}))))

(defn make-token []
  (str (java.util.UUID/randomUUID)))

(s/defn rate-limited? :- s/Bool
  [{:keys [turnstile nb-request-per-hour]} :- Limit]
  (if (has-space? turnstile nb-request-per-hour)
    false
    (do (expire-entries turnstile (System/currentTimeMillis))
        (not (has-space? turnstile nb-request-per-hour)))))

(def turnstile-expiration-ms (* 1000 60 60))

(s/defn with-turnstile :- Limit
  [{:keys [rate-limit-key] :as limit} :- Limit
   {:keys [redis-conf key-prefix] :as ctx}]
  (assoc limit
         :turnstile
         (map->RedisTurnstile {:conn-spec redis-conf
                               :pool {}
                               :name (cond->> rate-limit-key
                                       key-prefix (str key-prefix "-"))
                               :expiration-ms turnstile-expiration-ms})))

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

(s/defn rate-limit-headers
  [{:keys [turnstile nb-request-per-hour header-infix]} :- Limit]
  (let [remaining (space turnstile nb-request-per-hour)]
    (hash-map (format "X-RateLimit-%s-Limit" header-infix)
              nb-request-per-hour
              (format "X-RateLimit-%s-Remaining" header-infix)
              remaining)))

(s/defn compute-limits :- [Limit]
  [limit-fns :- [LimitFunction]
   request :- HttpRequest
   ctx]
  (->> limit-fns
       (keep (fn [f] (f request)))
       (map #(with-turnstile % ctx))))

(s/defn first-reached-limit :- (s/maybe Limit)
  [limits :- [Limit]]
  (some #(and (rate-limited? %) %) limits))

;;--- Middleware

(defn wrap-rate-limit
  "Middleware for the turnstile rate limiting service"
  [handler
   {:keys [redis-conf limit-fns rate-limit-handler key-prefix]
    :or {rate-limit-handler default-rate-limit-handler
         key-prefix ""} :as conf}]
  ;; Check the configuration
  (s/validate Conf conf)
  (fn [request]
    (let [limits (compute-limits limit-fns request {:redis-conf redis-conf
                                                    :key-prefix key-prefix})
          reached-limit (first-reached-limit limits)]
      (if reached-limit
        (rate-limit-handler
         request
         (next-slot (:turnstile reached-limit)
                    (:nb-request-per-hour reached-limit)
                    (System/currentTimeMillis)))
        (do (doseq [limit limits]
              (add-timed-item (:turnstile limit)
                              (java.util.UUID/randomUUID)
                              (System/currentTimeMillis)))
            (let [headers (->> limits
                               (map rate-limit-headers)
                               (apply merge))
                  response (handler request)]
              (update response :headers merge headers)))))))
