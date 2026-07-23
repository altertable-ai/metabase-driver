(ns metabase.driver.altertable
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.altertable.client :as client]
   [metabase.driver.altertable.query-processor]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.sync :as driver.s]
   [metabase.query-processor.compile :as qp.compile]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.util.i18n :refer [tru]]))

(comment metabase.driver.altertable.query-processor/keep-me)

(driver/register! :altertable, :parent #{:sql})

(defn- database-details [database]
  (driver.conn/effective-details database))

(defn- query->sql
  "Return native SQL for a compiled or native Metabase query."
  [query]
  (or (get-in query [:native :query])
      (some-> query qp.compile/compile :native :query)))

(defn- include-schema?
  "Apply Metabase schema-filters from database details.

  Reads `:schema-filters-type` / `:schema-filters-patterns` directly so filtering
  works even when plugin connection-properties have not been installed yet."
  [database schema-name]
  (let [details (database-details database)
        [inclusion exclusion]
        (case (:schema-filters-type details)
          "exclusion" [nil (:schema-filters-patterns details)]
          "inclusion" [(:schema-filters-patterns details) nil]
          [nil nil])]
    (driver.s/include-schema? inclusion exclusion schema-name)))

(defmethod driver/can-connect? :altertable
  [_driver details]
  (client/test-connection! details))

(defmethod driver/syncable-schemas :altertable
  [_driver database]
  (into #{}
        (filter #(include-schema? database %))
        (client/list-schemas! (database-details database))))

(defmethod driver/describe-database* :altertable
  [_driver database]
  {:tables (into #{}
                 (filter (fn [{:keys [schema]}]
                           (include-schema? database schema)))
                 (client/list-tables! (database-details database)))})

(defmethod driver/describe-table :altertable
  [_driver database table]
  (client/describe-table! (database-details database) table))

(defmethod driver/describe-fields :altertable
  [_driver database & {:keys [schema-names table-names]}]
  (let [schema-names (or (seq schema-names)
                         (seq (driver/syncable-schemas :altertable database)))]
    (filter (fn [{:keys [table-schema]}]
              (include-schema? database table-schema))
            (client/describe-fields! (database-details database)
                                     :schema-names schema-names
                                     :table-names table-names))))

(defmethod driver/execute-reducible-query :altertable
  [_driver {{sql :query, params :params} :native, :as outer-query} {:keys [canceled-chan]} respond]
  {:pre [(string? sql) (seq sql)]}
  (when (seq params)
    (throw (ex-info (tru "Altertable queries must be compiled with inlined parameters.")
                    {:type   qp.error-type/driver
                     :params (count params)})))
  (when (str/includes? sql "?")
    (throw (ex-info (tru "Altertable queries must not contain unbound parameter placeholders.")
                    {:type qp.error-type/driver
                     :sql  sql})))
  (let [database (driver-api/database (driver-api/metadata-provider))
        details  (driver.conn/effective-details database)
        max-rows (driver-api/determine-query-max-rows outer-query)
        timezone (some-> (driver-api/report-timezone-id-if-supported :altertable database) str)]
    (try
      (client/execute-query!
       details
       (cond-> {:query sql}
         max-rows  (assoc :max-rows max-rows)
         timezone  (assoc :timezone timezone))
       canceled-chan
       respond)
      (catch Exception e
        (throw (ex-info (tru "Error executing query: {0}" (ex-message e))
                        (cond-> {:type qp.error-type/invalid-query}
                          (client/query-canceled? e) (assoc :query-canceled? true))
                        e))))))

(defmethod driver/query-result-metadata :altertable
  [_ query]
  (when-let [sql (query->sql query)]
    (let [database (or (:database query)
                       (driver-api/database (driver-api/metadata-provider)))]
      (client/query-result-metadata (driver.conn/effective-details database) sql))))

(defmethod driver/query-canceled? :altertable
  [_driver ex]
  (client/query-canceled? ex))

(defmethod driver/table-known-to-not-exist? :altertable
  [_driver ex]
  (client/table-known-to-not-exist? ex))

(defmethod driver/table-exists? :altertable
  [_driver database table]
  (client/table-exists? (database-details database) table))

(defmethod driver/db-default-timezone :altertable
  [_driver database]
  (client/default-timezone (database-details database)))

(defmethod driver/humanize-connection-error-message :altertable
  [_ messages]
  (client/humanize-connection-error messages))

(defmethod driver/normalize-db-details :altertable
  [_ database]
  (update database :details client/sanitize-details))

(doseq [[feature supported?]
        {;; Read/query features validated for DuckDB-compatible Altertable SQL.
         :advanced-math-expressions          true
         :basic-aggregations                 true
         :binning                            true
         :case-sensitivity-string-filter-options true
         :date-arithmetics                   true
         :datetime-diff                      true
         :describe-fields                    true
         :distinct-where                     true
         :expression-aggregations            true
         :expressions                        true
         :expressions/date                   true
         :expressions/datetime               true
         :expressions/text                   true
         :expressions/today                  true
         :fingerprint                        true
         :full-join                          true
         :inner-join                         true
         :left-join                          true
         :metadata/table-existence-check     true
         :native-parameters                   true
         :native-temporal-units              true
         :nested-queries                     true
         :now                                true
         :percentile-aggregations            true
         :regex                              true
         :right-join                         true
         :schemas                            true
         ;; Kept false until Lakehouse QueryRequest.timezone changes DuckDB session semantics.
         :set-timezone                       false
         :standard-deviation-aggregations    true
         :temporal-extract                   true
         :window-functions/cumulative        true
         :window-functions/offset            true

         ;; Intentionally unsupported for this read-only HTTP driver.
         :actions                            false
         :actions/custom                     false
         :actions/data-editing               false
         :atomic-renames                     false
         :connection-impersonation           false
         :convert-timezone                   false
         :create-or-replace-table            false
         :database-routing                   false
         :dependencies/native                false
         :metadata/key-constraints           false
         :metadata/table-writable-check      false
         :native-parameter-card-reference    false
         :parameterized-sql                  false
         :parameters/table-reference          false
         :persist-models                     false
         :rename                             false
         :table-privileges                   false
         :transforms/python                  false
         :transforms/table                   false
         :uploads                            false}]
  (defmethod driver/database-supports? [:altertable feature]
    [_driver _feature _database]
    supported?))
