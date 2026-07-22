(ns metabase.driver.altertable-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.altertable]))

(deftest driver-registration-test
  (is (driver/available? :altertable)))

(deftest read-only-capabilities-test
  (doseq [feature [:database-routing
                   :dependencies/native
                   :metadata/key-constraints
                   :parameterized-sql
                   :persist-models
                   :uploads]]
    (is (false? (driver/database-supports? :altertable feature nil))
        (str feature " must not be advertised"))))
