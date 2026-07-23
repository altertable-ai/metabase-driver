(ns metabase.driver.altertable
  (:require
   [metabase.driver :as driver]
   [metabase.driver.altertable.client :as client]))

(driver/register! :altertable, :parent #{:sql})

(defmethod driver/can-connect? :altertable
  [_driver details]
  (client/test-connection! details))

(defmethod driver/describe-database* :altertable
  [_driver database]
  {:tables (set (client/list-tables! (:details database)))})

(defmethod driver/describe-table :altertable
  [_driver database table]
  (client/describe-table! (:details database) table))

(doseq [feature [:database-routing
                 :dependencies/native
                 :metadata/key-constraints
                 :parameterized-sql
                 :persist-models
                 :uploads]]
  (defmethod driver/database-supports? [:altertable feature]
    [_driver _feature _database]
    false))
