(ns metabase.driver.altertable-integration-test
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.altertable]
   [metabase.driver.altertable.client :as client]
   [metabase.driver.sql.query-processor :as sql.qp]))

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

(defn- create-table!
  [details table-name ddl]
  (client/execute-query!
   details
   {:query (str "CREATE TABLE " table-name " " ddl)}
   nil
   (fn [_ rows] (into [] rows))))

(defn- execute-sql!
  [details sql]
  (let [response (promise)]
    (client/execute-query!
     details
     {:query sql}
     nil
     (fn [_ rows] (deliver response (into [] rows))))
    @response))

(defn- format-sql [honeysql-form]
  (driver/with-driver :altertable
    (first (sql.qp/format-honeysql :altertable honeysql-form))))

(deftest ^:integration describe-table-finds-columns-test
  (let [details    (mock-details)
        database   {:details details}
        table-name (str "metabase_driver_fields_test_" (System/currentTimeMillis))]
    (create-table! details table-name "(id INTEGER, amount DECIMAL(4,2))")
    (let [{:keys [fields]} (driver/describe-table :altertable database {:name   table-name
                                                                         :schema "main"})
          fields-by-name (into {} (map (juxt :name identity) fields))]
      (is (= #{"id" "amount"} (set (keys fields-by-name))))
      (is (= :type/Integer (:base-type (fields-by-name "id"))))
      (is (= :type/Decimal (:base-type (fields-by-name "amount")))))))

(deftest ^:integration describe-fields-finds-columns-test
  (let [details    (mock-details)
        table-name (str "metabase_driver_bulk_fields_test_" (System/currentTimeMillis))]
    (create-table! details table-name "(id INTEGER, label VARCHAR)")
    (let [fields (client/describe-fields! details {:schema-names ["main"]
                                                   :table-names [table-name]})]
      (is (= #{[table-name "id"] [table-name "label"]}
             (set (map (juxt :table-name :name) fields)))))))

(deftest ^:integration describe-table-resolves-schema-test
  (let [details    (mock-details)
        table-name (str "metabase_driver_schema_lookup_test_" (System/currentTimeMillis))]
    (create-table! details table-name "(id INTEGER)")
    (let [{:keys [fields]} (client/describe-table! details {:name table-name})]
      (is (= #{"id"} (set (map :name fields)))))))

(deftest ^:integration describe-database-finds-tables-test
  (let [details    (mock-details)
        database   {:details details}
        table-name (str "metabase_driver_sync_test_" (System/currentTimeMillis))]
    (create-table! details table-name "(id INTEGER)")
    (let [{:keys [tables]} (driver/describe-database :altertable database)]
      (is (pos? (count tables))
          "expected describe-database to return tables from the catalog")
      (is (some #(= table-name (:name %)) tables)
          (str "expected describe-database to include created table " table-name)))))

(deftest ^:integration table-exists-and-timezone-test
  (let [details    (mock-details)
        table-name (str "metabase_driver_exists_test_" (System/currentTimeMillis))]
    (is (false? (client/table-exists? details {:name "definitely_missing_table" :schema "main"})))
    (create-table! details table-name "(id INTEGER)")
    (is (true? (client/table-exists? details {:name table-name :schema "main"})))
    (is (= "Etc/UTC" (client/default-timezone details)))))

(deftest ^:integration query-request-timezone-not-yet-honored-test
  (testing "Document that QueryRequest.timezone is currently ignored by the lakehouse session"
    (let [details (mock-details)
          response (promise)]
      (client/execute-query!
       details
       {:query "SELECT current_setting('TimeZone') AS tz"
        :timezone "Europe/Paris"}
       nil
       (fn [_ rows] (deliver response (into [] rows))))
      (is (= [["Etc/UTC"]] @response)
          "Keep :set-timezone false until this returns Europe/Paris"))))

(deftest ^:integration query-result-metadata-test
  (let [details (mock-details)
        [col]   (client/query-result-metadata details "SELECT 42 AS answer")]
    (is (= "answer" (:name col)))
    (is (= :type/Integer (:base_type col)))))

(deftest ^:integration sanitize-details-trims-values-test
  (is (= {:catalog "memory"
          :schema "main"
          :username "testuser"
          :password "testpass"
          :base-url "https://api.altertable.ai"
          :compute-size :AUTO
          :connect-timeout-seconds 5
          :request-timeout-seconds 60
          :schema-filters-type "inclusion"
          :schema-filters-patterns "main"}
         (client/sanitize-details {:catalog " memory "
                                   :schema " main "
                                   :username " testuser "
                                   :password " testpass "
                                   :schema-filters-type "inclusion"
                                   :schema-filters-patterns "main"}))))

(deftest ^:integration duckdb-feature-sql-parity-test
  (let [details (mock-details)
        cases
        [["filter-literal"
          "SELECT 1 AS ok WHERE 7 = 7 AND 'O''Reilly' = 'O''Reilly'"
          [[1]]]
         ["inner-join"
          "SELECT a.id FROM (SELECT 1 AS id) a INNER JOIN (SELECT 1 AS id) b ON a.id = b.id"
          [[1]]]
         ["nested-query"
          "SELECT count FROM (SELECT COUNT(*) AS count FROM (SELECT 1) t) nested"
          [[1]]]
         ["basic-aggregation"
          "SELECT COUNT(*) AS c, SUM(x) AS s FROM (SELECT 1 AS x UNION ALL SELECT 2) t"
          [[2 3]]]
         ["stddev"
          "SELECT ROUND(STDDEV_POP(x), 2) AS sd FROM (SELECT 1.0 AS x UNION ALL SELECT 3.0) t"
          [[1.00M]]]
         ["percentile"
          "SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY x) AS p50 FROM (SELECT 1 AS x UNION ALL SELECT 3) t"
          [[2.0M]]]
         ["date-trunc"
          "SELECT CAST(date_trunc('day', TIMESTAMP '2024-01-15 13:45:00') AS DATE) AS d"
          ;; mock/DuckDB may return LocalDate or string depending on result mapping
          :date-day]
         ["date-arithmetics"
          "SELECT CAST((CAST(TIMESTAMP '2024-01-01' AS TIMESTAMP WITH TIME ZONE) + INTERVAL '3' day) AS DATE) AS d"
          :date-plus-3]
         ["regex"
          "SELECT regexp_extract('abc123', '[0-9]+') AS digits"
          [["123"]]]
         ["window"
          "SELECT SUM(x) OVER () AS total FROM (SELECT 1 AS x UNION ALL SELECT 2) t ORDER BY x"
          [[3] [3]]]]]
    (doseq [[label sql expected] cases]
      (testing label
        (let [rows (execute-sql! details sql)]
          (case expected
            :date-day
            (is (some? (ffirst rows)))

            :date-plus-3
            (is (some? (ffirst rows)))

            (is (= expected rows))))))))

(deftest ^:integration compiled-honeysql-executes-test
  (let [details (mock-details)
        sql (format-sql {:select [[[:inline 1] :ok]]
                         :where  [:and
                                  [:= 7 7]
                                  [:= "O'Reilly" "O'Reilly"]]})]
    (is (not (str/includes? sql "?")))
    (is (re-find #"O''Reilly" sql))
    (is (= [[1]] (execute-sql! details sql)))))

(deftest ^:integration max-rows-and-cancel-compiled-query-test
  (let [details (mock-details)
        sql "SELECT * FROM (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3) t"
        limited (promise)
        cancel-chan (async/chan 1)
        canceled (promise)]
    (client/execute-query!
     details
     {:query sql :max-rows 2}
     nil
     (fn [_ rows] (deliver limited (into [] rows))))
    (is (= 2 (count @limited)))
    (with-redefs [client/cancel-query!
                  (fn [_ metadata]
                    (deliver canceled [(some-> metadata (.get "query_id") .asText)
                                       (some-> metadata (.get "session_id") .asText)]))]
      (client/execute-query!
       details
       {:query sql}
       cancel-chan
       (fn [_ rows]
         (async/>!! cancel-chan true)
         (is (vector? (deref canceled 2000 nil)))
         (into [] rows))))
    (is (every? seq @canceled))))

(deftest ^:integration view-discovery-test
  (let [details    (mock-details)
        database   {:engine :altertable :details details}
        table-name (str "metabase_driver_view_base_" (System/currentTimeMillis))
        view-name  (str "metabase_driver_view_" (System/currentTimeMillis))]
    (create-table! details table-name "(id INTEGER, label VARCHAR)")
    (client/execute-query!
     details
     {:query (str "CREATE VIEW " view-name " AS SELECT id, label FROM " table-name)}
     nil
     (fn [_ rows] (into [] rows)))
    (let [{:keys [tables]} (driver/describe-database :altertable database)]
      (is (some #(= view-name (:name %)) tables)
          "ordinary views must appear in describe-database"))
    (is (true? (driver/table-exists? :altertable database {:name view-name :schema "main"})))
    (let [{:keys [fields]} (driver/describe-table :altertable database {:name view-name :schema "main"})]
      (is (= #{"id" "label"} (set (map :name fields)))))
    (let [response (promise)]
      (client/execute-query!
       details
       {:query (str "SELECT * FROM " view-name)}
       nil
       (fn [_ rows] (deliver response (into [] rows))))
      (is (vector? @response)))))

(deftest ^:integration syncable-schemas-and-default-schema-test
  (let [details     (assoc (mock-details) :schema "main")
        database    {:engine :altertable :details details}
        other-schema (str "alt_schema_" (System/currentTimeMillis))
        table-name   (str "metabase_driver_schema_sync_" (System/currentTimeMillis))]
    (client/execute-query!
     details
     {:query (str "CREATE SCHEMA " other-schema)}
     nil
     (fn [_ rows] (into [] rows)))
    (client/execute-query!
     details
     {:query (str "CREATE TABLE " other-schema "." table-name " (id INTEGER)")}
     nil
     (fn [_ rows] (into [] rows)))
    (let [schemas (driver/syncable-schemas :altertable database)]
      (is (contains? schemas "main"))
      (is (contains? schemas other-schema)
          "default query schema must not prevent discovering other schemas"))
    (let [{:keys [tables]} (driver/describe-database :altertable database)]
      (is (some #(and (= other-schema (:schema %)) (= table-name (:name %))) tables)))
    (let [filtered {:engine :altertable
                    :details (assoc details
                                    :schema-filters-type "inclusion"
                                    :schema-filters-patterns "main")}
          schemas (driver/syncable-schemas :altertable filtered)
          {:keys [tables]} (driver/describe-database :altertable filtered)]
      (is (= #{"main"} schemas))
      (is (not-any? #(= other-schema (:schema %)) tables)))))
