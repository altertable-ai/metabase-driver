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

(deftest ^:integration execute-query-uses-declared-database-types-test
  (let [response (promise)]
    (client/execute-query!
     (mock-details)
     {:query (str "SELECT DATE '2024-01-15' AS d, "
                  "TIMESTAMP '2024-01-15 13:45:00' AS ts, "
                  "TIMESTAMPTZ '2024-01-15 13:45:00+01:00' AS tstz, "
                  "1.5::DOUBLE AS measurement")}
     nil
     (fn [metadata rows]
       (deliver response [metadata (into [] rows)])))
    (let [[metadata _rows] @response]
      (is (= [{:name          "d"
               :database_type "DATE"
               :base_type     :type/Date
               :effective_type :type/Date}
              {:name          "ts"
               :database_type "TIMESTAMP"
               :base_type     :type/DateTime
               :effective_type :type/DateTime}
              {:name          "tstz"
               :database_type "TIMESTAMP WITH TIME ZONE"
               :base_type     :type/DateTimeWithTZ
               :effective_type :type/DateTimeWithTZ}
              {:name          "measurement"
               :database_type "DOUBLE"
               :base_type     :type/Float
               :effective_type :type/Float}]
             (:cols metadata))))))

(deftest ^:integration execute-query-falls-back-to-row-inference-for-unparsed-duckdb-sql-test
  (let [details  (mock-details)
        query-sql "FROM range(1) SELECT range AS n"
        response (promise)]
    (is (= [] (client/query-result-metadata details query-sql)))
    (client/execute-query!
     details
     {:query query-sql}
     nil
     (fn [metadata rows]
       (deliver response [metadata (into [] rows)])))
    (let [[metadata rows] @response]
      (is (= [{:name           "n"
               :base_type      :type/Integer
               :effective_type :type/Integer}]
             (:cols metadata)))
      (is (= [[0]] rows)))))

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

(deftest ^:integration catalog-has-tables-test
  (let [details    (mock-details)
        table-name (str "metabase_driver_tables_test_" (System/currentTimeMillis))]
    (client/execute-query!
     details
     {:query (str "CREATE TABLE " table-name " (id INTEGER, label VARCHAR)")}
     nil
     (fn [_ rows] (into [] rows)))
    (let [tables (client/list-tables! details)]
      (is (pos? (count tables))
          "expected the catalog to expose at least one table")
      (is (some #(= table-name (:name %)) tables)
          (str "expected to find created table " table-name)))
    (let [{:keys [fields]} (client/describe-table! details {:name table-name :schema "main"})]
      (is (= #{"id" "label"} (set (map :name fields)))))))

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
