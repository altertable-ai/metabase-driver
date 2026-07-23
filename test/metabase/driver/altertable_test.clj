(ns metabase.driver.altertable-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.altertable]
   [metabase.driver.altertable.client :as client]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.settings :as driver.settings]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.util :as driver.u]
   [metabase.query-processor.timezone :as qp.timezone]))

(deftest driver-registration-test
  (is (driver/available? :altertable)))

(deftest can-connect-test
  (with-redefs [client/test-connection! (constantly true)]
    (is (true? (driver/can-connect? :altertable {:catalog "lake"
                                                  :username "alice"
                                                  :password "secret"})))))

(def ^:private expected-supported-features
  #{:advanced-math-expressions
    :basic-aggregations
    :binning
    :case-sensitivity-string-filter-options
    :date-arithmetics
    :datetime-diff
    :describe-fields
    :distinct-where
    :expression-aggregations
    :expressions
    :expressions/date
    :expressions/datetime
    :expressions/text
    :expressions/today
    :fingerprint
    :full-join
    :inner-join
    :left-join
    :metadata/table-existence-check
    :native-parameters
    :native-temporal-units
    :nested-queries
    :now
    :percentile-aggregations
    :regex
    :right-join
    :schemas
    :standard-deviation-aggregations
    :temporal-extract
    :window-functions/cumulative
    :window-functions/offset})

(def ^:private expected-unsupported-features
  #{:actions
    :actions/custom
    :actions/data-editing
    :atomic-renames
    :connection-impersonation
    :convert-timezone
    :create-or-replace-table
    :database-routing
    :dependencies/native
    :metadata/key-constraints
    :metadata/table-writable-check
    :native-parameter-card-reference
    :parameterized-sql
    :parameters/table-reference
    :persist-models
    :rename
    :set-timezone
    :table-privileges
    :transforms/python
    :transforms/table
    :uploads})

(deftest read-only-capabilities-test
  (doseq [feature expected-supported-features]
    (is (true? (driver/database-supports? :altertable feature nil))
        (str feature " must be advertised")))
  (doseq [feature expected-unsupported-features]
    (is (false? (driver/database-supports? :altertable feature nil))
        (str feature " must not be advertised"))))

(deftest report-timezone-capability-gate-test
  (testing "report timezone stays gated off until QueryRequest.timezone is honored"
    (is (false? (driver.u/supports? :altertable :set-timezone nil)))
    (with-redefs [driver.settings/report-timezone (constantly "Europe/Paris")]
      (is (nil? (qp.timezone/report-timezone-id-if-supported :altertable
                                                             {:id 1 :engine :altertable :details {}}))))))

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

(deftest execute-reducible-query-rejects-bind-params-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"inlined parameters"
                        (driver/execute-reducible-query :altertable
                                                        {:native {:query "SELECT ?" :params [1]}}
                                                        {:canceled-chan nil}
                                                        (fn [_ _]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unbound parameter placeholders"
                        (driver/execute-reducible-query :altertable
                                                        {:native {:query "SELECT ?"}}
                                                        {:canceled-chan nil}
                                                        (fn [_ _])))))

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

(deftest native-template-parameter-inlining-test
  (testing "HoneySQL inlining escapes quotes so executable SQL has no bind params"
    (driver/with-driver :altertable
      (let [[sql & params] (sql.qp/format-honeysql
                            :altertable
                            {:select [[:*]]
                             :from   [[:events]]
                             :where  [:= :label "O'Reilly; DROP TABLE events--"]})]
        (is (empty? params))
        (is (not (str/includes? sql "?")))
        (is (re-find #"O''Reilly; DROP TABLE events--" sql)
            "quotes in substituted values must be escaped")))))

(deftest syncable-schemas-test
  (with-redefs [client/list-schemas! (constantly ["main" "analytics" "temp"])]
    (is (= #{"main" "analytics" "temp"}
           (driver/syncable-schemas :altertable
                                    {:engine :altertable
                                     :details {:catalog "lake"
                                               :username "alice"
                                               :password "secret"}})))
    (is (= #{"main"}
           (driver/syncable-schemas :altertable
                                    {:engine :altertable
                                     :details {:catalog "lake"
                                               :username "alice"
                                               :password "secret"
                                               :schema-filters-type "inclusion"
                                               :schema-filters-patterns "main"}})))))

(deftest describe-database-respects-schema-filters-not-default-schema-test
  (with-redefs [client/list-tables! (constantly [{:schema "main" :name "a"}
                                                 {:schema "analytics" :name "b"}])]
    (is (= #{{:schema "main" :name "a"} {:schema "analytics" :name "b"}}
           (:tables (driver/describe-database* :altertable
                                               {:engine :altertable
                                                :details {:catalog "lake"
                                                          :schema "main"
                                                          :username "alice"
                                                          :password "secret"}})))
        "default query schema must not hide other schemas during sync")
    (is (= #{{:schema "main" :name "a"}}
           (:tables (driver/describe-database* :altertable
                                               {:engine :altertable
                                                :details {:catalog "lake"
                                                          :schema "main"
                                                          :username "alice"
                                                          :password "secret"
                                                          :schema-filters-type "inclusion"
                                                          :schema-filters-patterns "main"}}))))))
