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
    :name-in-headers s/Str}
   (st/optional-keys
    {:turnstile (s/protocol Turnstile)})))

(s/defschema LimitFunction
  (s/=> Limit HttpRequest))

(s/defschema RedisSpec
  (st/optional-keys
   {:host s/Str
    :port s/Int
    :uri s/Str
    :password s/Str
    :db s/Int}))

(s/defschema RedisConn
  (st/optional-keys
   {:spec RedisSpec
    :pool s/Any}))

(s/defschema Conf
  (st/merge
   {:redis-conn RedisConn
    :limit-fns [LimitFunction]}
   (st/optional-keys
    {:key-prefix s/Str
     :remaining-header-enabled s/Bool
     :rate-limit-handler (s/=> HttpResponse HttpRequest s/Int)})))

;;--- Limits

(s/defn ip-limit [n] :- LimitFunction
  (fn [request]
    {:nb-request-per-hour n
     :rate-limit-key (:remote-addr request)
     :name-in-headers "IP"}))

(s/defn header-limit :- LimitFunction
  ([n header name-in-headers]
   (fn [request]
     (when-let [header (get-in request [:headers header])]
       {:nb-request-per-hour n
        :rate-limit-key header
        :name-in-headers name-in-headers}))))

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
   {:keys [redis-conn key-prefix] :as ctx}]
  (assoc limit
         :turnstile
         (map->RedisTurnstile {:conn-spec (:spec redis-conn)
                               :pool (:pool redis-conn)
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
  [request next-slot-in-sec limit]
  {:status 429
   :headers {"Content-Type" "application/json"
             "Retry-After" (str next-slot-in-sec)}
   :body "{\"error\": \"Too Many Requests\"}"})

(s/defn rate-limit-headers
  [{:keys [turnstile nb-request-per-hour name-in-headers]} :- Limit
   remaining-header-enabled :- s/Bool]
  (merge (hash-map (format "X-RateLimit-%s-Limit" name-in-headers)
                   (str nb-request-per-hour))
         (when remaining-header-enabled
           (let [remaining
                 (do (expire-entries turnstile (System/currentTimeMillis))
                     (space turnstile nb-request-per-hour))]
             (hash-map (format "X-RateLimit-%s-Remaining" name-in-headers)
                       (str remaining))))))

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
   {:keys [redis-conn limit-fns rate-limit-handler key-prefix
           remaining-header-enabled]
    :or {rate-limit-handler default-rate-limit-handler
         key-prefix ""
         remaining-header-enabled false} :as conf}]
  ;; Check the configuration
  (s/validate Conf conf)
  (fn [request]
    (try
      (let [limits (compute-limits limit-fns request {:redis-conn redis-conn
                                                      :key-prefix key-prefix})
            reached-limit (first-reached-limit limits)]
        (if reached-limit
          (let [next-slot-sec (next-slot-in-sec
                               (next-slot (:turnstile reached-limit)
                                          (:nb-request-per-hour reached-limit)
                                          (System/currentTimeMillis)))]
            (rate-limit-handler request next-slot-sec reached-limit))
          (do (doseq [limit limits]
                (add-timed-item (:turnstile limit)
                                (java.util.UUID/randomUUID)
                                (System/currentTimeMillis)))
              (let [headers (->> limits
                                 (map #(rate-limit-headers % remaining-header-enabled))
                                 (apply merge))
                    response (try
                               (handler request)
                               (catch Exception e
                                 (throw (ex-info (.getMessage e)
                                                 {:origin :other-handlers}
                                                 e))))]
                (update response :headers merge headers)))))
      (catch Exception e
        (let [origin (-> (ex-data e) :origin)]
          (if (= origin :other-handlers)
            (throw (.getCause e))
            (throw (ex-info (.getMessage e)
                            {:origin :ring-turnstile-middleware}
                            e))))))))

