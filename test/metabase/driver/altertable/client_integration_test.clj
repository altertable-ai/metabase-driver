(ns metabase.driver.altertable.client-integration-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer :all]
   [metabase.driver.altertable.client :as client]))

(defn- mock-details
  ([] (mock-details "testpass"))
  ([password]
   {:base-url (or (System/getenv "ALTERTABLE_MOCK_URL")
                  "http://host.docker.internal:15000")
    :catalog "memory"
    :schema "main"
    :username "testuser"
    :password password
    :request-timeout-seconds 10}))

(deftest ^:integration test-connection-test
  (is (true? (client/test-connection! (mock-details)))))

(deftest ^:integration execute-query-test
  (let [response (promise)]
    (client/execute-query!
     (mock-details)
     {:query "SELECT 1 AS id, 1.25::DECIMAL(4,2) AS amount UNION ALL SELECT 2, 2.50"
      :max-rows 1}
     nil
     (fn [metadata rows]
       (deliver response [metadata (into [] rows)])))
    (let [[metadata rows] @response]
      (is (= ["id" "amount"] (mapv :name (:cols metadata))))
      (is (= [:type/Integer :type/Decimal] (mapv :base_type (:cols metadata))))
      (is (= [[1 1.25M]] rows)))))

(deftest ^:integration authentication-errors-are-sanitized-test
  (let [secret "definitely-not-valid"
        error  (try
                 (client/execute-query! (mock-details secret)
                                        {:query "SELECT 1"}
                                        nil
                                        (fn [_ _]))
                 nil
                 (catch Exception e e))]
    (is (some? error))
    (is (= 401 (:status-code (ex-data error))))
    (is (not (re-find (re-pattern secret) (pr-str error))))))

(deftest ^:integration cancellation-uses-stream-identifiers-test
  (let [cancel-chan (async/chan 1)
        canceled    (promise)]
    (with-redefs [client/cancel-query!
                  (fn [_ metadata]
                    (deliver canceled [(some-> metadata (.get "query_id") .asText)
                                       (some-> metadata (.get "session_id") .asText)]))]
      (client/execute-query!
       (mock-details)
       {:query "SELECT 1"}
       cancel-chan
       (fn [_ rows]
         (async/>!! cancel-chan true)
         (is (vector? (deref canceled 2000 nil)))
         (into [] rows))))
    (is (every? seq @canceled))))
