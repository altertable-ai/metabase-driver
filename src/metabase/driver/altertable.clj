(ns metabase.driver.altertable
  (:require
   [metabase.driver :as driver]
   [metabase.driver.altertable.client]))

(driver/register! :altertable, :parent #{:sql})

(doseq [feature [:database-routing
                 :dependencies/native
                 :metadata/key-constraints
                 :parameterized-sql
                 :persist-models
                 :uploads]]
  (defmethod driver/database-supports? [:altertable feature]
    [_driver _feature _database]
    false))
