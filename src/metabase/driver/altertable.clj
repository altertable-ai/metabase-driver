(ns metabase.driver.altertable
  (:require
   [metabase.driver :as driver]
   [metabase.driver.altertable.client :as client]))

(driver/register! :altertable, :parent #{:sql})

(defmethod driver/can-connect? :altertable
  [_driver details]
  (client/test-connection! details))

(doseq [feature [:database-routing
                 :dependencies/native
                 :metadata/key-constraints
                 :parameterized-sql
                 :persist-models
                 :uploads]]
  (defmethod driver/database-supports? [:altertable feature]
    [_driver _feature _database]
    false))
