(ns ring.middleware.turnstile-test
  (:require [ring.middleware.turnstile :as sut]
            [clojure.test :as t :refer [deftest testing is are use-fixtures]]
            [ring.mock.request :refer [request header]]
            [schema.test :refer [validate-schemas]]
            [taoensso.carmine :as car]))

(defn reset-limits!
  []
  (car/wcar {} {}
            (car/flushdb)))

(defn reset-limits-fixture
  [f]
  (reset-limits!)
  (f)
  (reset-limits!))

(use-fixtures :once validate-schemas)

(use-fixtures :each reset-limits-fixture)



(deftest combine-limits-test
  (let [ip-limit (sut/ip-limit 4)
        header-limit (sut/header-limit 4 "x-id")
        {:keys [key-fn rate-limited-fn limit]}
        (sut/combine-limits [ip-limit header-limit] 5)]
    (is (= 5 limit)
        "The combined limit overrides all specific limits")
    (is (= "10.2.3.4-12345"
           (key-fn {:remote-addr "10.2.3.4"
                    :headers {"x-id" "12345"}}))
        "The rate limit key is composed by the key of the combined limits")
    (is (rate-limited-fn {:remote-addr "10.2.3.4"
                          :headers {"x-id" "12345"}})
        "Rate limit if the `:rate-limited-fn` of both limits return true")
    (is (not (rate-limited-fn {:remote-addr "10.2.3.4"
                               :headers {}}))
        "No rate limit if one of the  `:rate-limited-fn` returns false")))

(deftest limit-to-apply-test
  (let [ip-limit (sut/ip-limit 4)
        header-limit (sut/header-limit 4 "x-id")
        exception-limit {:key-fn (constantly "")
                         :rate-limited-fn
                         (fn [request]
                           (= "1234"
                              (get-in request [:headers "x-id"])))
                         :limit sut/no-limit}
        limits [exception-limit
                header-limit
                ip-limit]]
    (is (= ip-limit
           (sut/limit-to-apply {:remote-addr "10.2.3.4"} limits)))
    (is (= exception-limit
           (sut/limit-to-apply {:remote-addr "10.2.3.4"
                                :headers {"x-id" "1234"}}
                               limits)))
    (is (= header-limit
           (sut/limit-to-apply {:remote-addr "10.2.3.4"
                                :headers {"x-id" "2222"}}
                               limits)))))

(deftest default-rate-limit-handler-test
  (is (= {:status 429
          :headers {"Content-Type" "application/json"
                    "Retry-After" 3}
          :body "{\"error\": \"Too Many Requests\"}"}
         (sut/default-rate-limit-handler {} 2500))))

(deftest wrap-rate-limit-test
  (testing "One limit"
    (let [app (-> (fn [req] {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body "{}"})
                  (sut/wrap-rate-limit {:redis-conf {}
                                        :limits [(sut/ip-limit 5)]}))]
      (testing "Rate limit headers"
        (let [response (-> (request :get "/") app)]
          (is (= 4 (get-in response [:headers "X-RateLimit-Remaining"])))
          (is (= 5 (get-in response [:headers "X-RateLimit-Limit"])))))
      (testing "Rate limit status and response"
        (dotimes [_ 5] (-> (request :get "/") app))
        (let [response (-> (request :get "/") app)]
          (is (= 429 (:status response)))
          (is (= 3600 (get-in response [:headers "Retry-After"])))
          (is (= "{\"error\": \"Too Many Requests\"}"
                 (:body response)))))))
  (testing "Custom rate limit handler"
    (let [app (-> (fn [req] {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body "{}"})
                  (sut/wrap-rate-limit {:redis-conf {}
                                        :limits [(sut/ip-limit 5)]
                                        :rate-limit-handler
                                        (fn [request next-slot-in-ms]
                                          {:status 429
                                           :headers {"Content-Type" "application/text"}
                                           :body "Too many requests, retry later"})}))]
      (let [response (-> (request :get "/") app)]
        (is (= 429 (:status response)))
        (is (= "Too many requests, retry later"
               (:body response))))))
  (testing "Mulitple limiters"
    (reset-limits!)
    (let [ip-limit (sut/ip-limit 5)
          header-limit (sut/header-limit 10 "x-id")
          exception-limit {:key-fn (constantly "")
                           :rate-limited-fn
                           (fn [request]
                             (= "1234"
                                (get-in request [:headers "x-id"])))
                           :limit sut/no-limit}
          app (-> (fn [req] {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body "{}"})
                  (sut/wrap-rate-limit {:redis-conf {}
                                        :limits [exception-limit
                                                 header-limit
                                                 ip-limit]}))]
      (testing "Header limit when the `x-id` header is set"
        (let [response (-> (request :get "/") (header "x-id" "1") app)]
          (is (= 9 (get-in response [:headers "X-RateLimit-Remaining"])))
          (is (= 10 (get-in response [:headers "X-RateLimit-Limit"]))))
        (let [response (-> (request :get "/") (header "x-id" "2")app)]
          (is (= 9 (get-in response [:headers "X-RateLimit-Remaining"])))
          (is (= 10 (get-in response [:headers "X-RateLimit-Limit"])))))
      (testing "IP limit when the `x-id` header is not set"
        (let [response (-> (request :get "/") app)]
          (is (= 4 (get-in response [:headers "X-RateLimit-Remaining"])))
          (is (= 5 (get-in response [:headers "X-RateLimit-Limit"]))))
        (let [response (-> (request :get "/") (header "x-id" "1") app)]
          (is (= 8 (get-in response [:headers "X-RateLimit-Remaining"])))
          (is (= 10 (get-in response [:headers "X-RateLimit-Limit"]))))
        (let [response (-> (request :get "/") (header "x-id" "2") app)]
          (is (= 8 (get-in response [:headers "X-RateLimit-Remaining"])))
          (is (= 10 (get-in response [:headers "X-RateLimit-Limit"])))))
      (testing "Unlimited limit for the exception limit"
        (let [response (-> (request :get "/") (header "x-id" "1234") app)]
          (is (not (contains? (:headers response) "X-RateLimit-Remaining"))))
        (let [response (-> (request :get "/") (header "x-id" "1") app)]
          (is (= 7 (get-in response [:headers "X-RateLimit-Remaining"])))
          (is (= 10 (get-in response [:headers "X-RateLimit-Limit"]))))))))

(deftest wrap-rate-limit-key-prefix-test
  (let [app1 (-> (fn [req] {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body "{}"})
                (sut/wrap-rate-limit {:redis-conf {}
                                      :key-prefix "api-1"
                                      :limits [(sut/ip-limit 5)]}))
        app2 (-> (fn [req] {:status 200
                            :headers {"Content-Type" "application/json"}
                            :body "{}"})
                 (sut/wrap-rate-limit {:redis-conf {}
                                       :key-prefix "api-2"
                                       :limits [(sut/ip-limit 5)]}))]
    (let [response (-> (request :get "/") app1)]
      (is (= 4 (get-in response [:headers "X-RateLimit-Remaining"]))))
    (let [response (-> (request :get "/") app2)]
      (is (= 4 (get-in response [:headers "X-RateLimit-Remaining"]))))))
