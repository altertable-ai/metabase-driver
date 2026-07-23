(ns metabase.driver.altertable-integration-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.altertable]
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

(defn- create-table!
  [details table-name ddl]
  (client/execute-query!
   details
   {:query (str "CREATE TABLE " table-name " " ddl)}
   nil
   (fn [_ rows] (into [] rows))))

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
