(ns metabase.driver.altertable-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.altertable]
   [metabase.driver.altertable.client :as client]))

(deftest driver-registration-test
  (is (driver/available? :altertable)))

(deftest can-connect-test
  (with-redefs [client/test-connection! (constantly true)]
    (is (true? (driver/can-connect? :altertable {:catalog "lake"
                                                  :username "alice"
                                                  :password "secret"})))))

(deftest read-only-capabilities-test
  (doseq [feature [:database-routing
                   :dependencies/native
                   :metadata/key-constraints
                   :parameterized-sql
                   :persist-models
                   :uploads]]
    (is (false? (driver/database-supports? :altertable feature nil))
        (str feature " must not be advertised"))))
