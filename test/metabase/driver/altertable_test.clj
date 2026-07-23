(ns metabase.driver.altertable-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.altertable]
   [metabase.driver.altertable.client :as client]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.connection :as driver.conn]))

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

(deftest normalize-db-details-test
  (is (= {:details {:catalog "lake"
                    :schema "reporting"
                    :username "alice"
                    :password "secret"
                    :base-url "https://api.altertable.ai"
                    :compute-size :AUTO
                    :connect-timeout-seconds 5
                    :request-timeout-seconds 60}}
         (driver/normalize-db-details :altertable
                                      {:details {:catalog " lake "
                                                 :schema " reporting "
                                                 :username "alice"
                                                 :password "secret"}}))))

(deftest humanize-connection-error-message-test
  (is (= :username-or-password-incorrect
         (driver/humanize-connection-error-message :altertable
                                                   ["Altertable query failed (HTTP 401)."])))
  (is (= :database-name-incorrect
         (driver/humanize-connection-error-message :altertable
                                                   ["Catalog is required."]))))

(deftest query-canceled-test
  (is (true? (driver/query-canceled? :altertable
                                       (ex-info "canceled"
                                                {:query-canceled? true}))))
  (is (true? (driver/query-canceled? :altertable
                                       (ex-info "Altertable cancelQuery failed."
                                                {:type :altertable/api-error
                                                 :operation "cancelQuery"})))))

(deftest table-known-to-not-exist-test
  (is (true? (driver/table-known-to-not-exist? :altertable
                                                (ex-info "Catalog Error: Table with name t does not exist!"
                                                         {:api-message "Catalog Error: Table with name t does not exist!"})))))

(deftest execute-reducible-query-test
  (with-redefs [driver-api/database (constantly {:details {:catalog "lake"
                                                           :username "alice"
                                                           :password "secret"}})
                driver-api/metadata-provider (constantly ::provider)
                driver-api/determine-query-max-rows (constantly 100)
                driver-api/report-timezone-id-if-supported (constantly "UTC")
                driver.conn/effective-details (fn [db] (:details db))
                client/execute-query! (fn [details native-query cancel-chan respond]
                                        (is (= "lake" (:catalog details)))
                                        (is (= "SELECT 1" (:query native-query)))
                                        (is (= 100 (:max-rows native-query)))
                                        (is (= "UTC" (:timezone native-query)))
                                        (is (nil? cancel-chan))
                                        (respond {:cols [{:name "id" :base_type :type/Integer}]} [[1]]))]
    (let [response (promise)]
      (driver/execute-reducible-query :altertable
                                      {:native {:query "SELECT 1"}}
                                      {:canceled-chan nil}
                                      (fn [metadata rows]
                                        (deliver response [metadata (into [] rows)])))
      (let [[metadata rows] @response]
        (is (= "id" (get-in metadata [:cols 0 :name])))
        (is (= [[1]] rows))))))

(deftest query-result-metadata-test
  (with-redefs [client/query-result-metadata (constantly [{:name "id" :base_type :type/Integer}])]
    (is (= [{:name "id" :base_type :type/Integer}]
           (driver/query-result-metadata :altertable
                                         {:database {:details {:catalog "lake"
                                                               :username "alice"
                                                               :password "secret"}}
                                          :native   {:query "SELECT id FROM t"}})))))

(deftest describe-fields-test
  (with-redefs [client/describe-fields! (constantly [{:table-name "events"
                                                       :table-schema "main"
                                                       :name "id"
                                                       :base-type :type/Integer
                                                       :database-type "INTEGER"
                                                       :database-position 0}])]
    (let [fields (driver/describe-fields :altertable
                                         {:details {:catalog "lake"
                                                    :username "alice"
                                                    :password "secret"}}
                                         :schema-names ["main"]
                                         :table-names ["events"])]
      (is (= "id" (:name (first fields)))))))

(deftest table-exists-test
  (with-redefs [client/table-exists? (constantly true)]
    (is (true? (driver/table-exists? :altertable
                                      {:details {:catalog "lake"
                                                 :username "alice"
                                                 :password "secret"}}
                                      {:name "events" :schema "main"})))))

(deftest db-default-timezone-test
  (with-redefs [client/default-timezone (constantly "Etc/UTC")]
    (is (= "Etc/UTC"
           (driver/db-default-timezone :altertable
                                       {:details {:catalog "lake"
                                                  :username "alice"
                                                  :password "secret"}})))))

(deftest describe-fields-capability-test
  (is (true? (driver/database-supports? :altertable :describe-fields nil))))
