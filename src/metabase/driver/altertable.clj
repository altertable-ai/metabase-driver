(ns metabase.driver.altertable
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.altertable.client :as client]
   [metabase.driver.connection :as driver.conn]
   [metabase.query-processor.compile :as qp.compile]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.util.i18n :refer [tru]]))

(driver/register! :altertable, :parent #{:sql})

(defn- database-details [database]
  (driver.conn/effective-details database))

(defn- query->sql
  "Return native SQL for a compiled or native Metabase query."
  [query]
  (or (get-in query [:native :query])
      (some-> query qp.compile/compile :native :query)))

(defmethod driver/can-connect? :altertable
  [_driver details]
  (client/test-connection! details))

(defmethod driver/describe-database* :altertable
  [_driver database]
  {:tables (set (client/list-tables! (database-details database)))})

(defmethod driver/describe-table :altertable
  [_driver database table]
  (client/describe-table! (database-details database) table))

(defmethod driver/describe-fields :altertable
  [_driver database & args]
  (apply client/describe-fields! (database-details database) args))

(defmethod driver/database-supports? [:altertable :describe-fields]
  [_driver _feature _database]
  true)

(defmethod driver/execute-reducible-query :altertable
  [_driver {{sql :query} :native, :as outer-query} {:keys [canceled-chan]} respond]
  {:pre [(string? sql) (seq sql)]}
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

(doseq [feature [:database-routing
                 :dependencies/native
                 :metadata/key-constraints
                 :parameterized-sql
                 :persist-models
                 :uploads]]
  (defmethod driver/database-supports? [:altertable feature]
    [_driver _feature _database]
    false))
