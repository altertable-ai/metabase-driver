(ns metabase.driver.altertable.client-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.altertable.client :as client]))

(deftest normalize-details-test
  (testing "normalizes defaults and credentials"
    (is (= {:base-url               "https://api.altertable.ai"
            :catalog                "analytics"
            :schema                 nil
            :username               "alice"
            :password               "secret"
            :compute-size           :AUTO
            :connect-timeout-seconds 5
            :request-timeout-seconds 60}
           (client/normalize-details {:catalog " analytics "
                                      :username "alice"
                                      :password "secret"}))))

  (testing "trims an optional schema"
    (is (= "reporting"
           (:schema (client/normalize-details {:catalog "lake"
                                               :schema " reporting "
                                               :username "alice"
                                               :password "secret"})))))

  (testing "requires a catalog"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Catalog is required"
                          (client/normalize-details {:username "alice"
                                                     :password "secret"}))))

  (testing "requires username and password together"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username and password"
                          (client/normalize-details {:catalog "lake"
                                                     :username "alice"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Username and password are required"
                          (client/normalize-details {:catalog "lake"}))))

  (testing "rejects malformed URLs without leaking secrets"
    (let [secret "do-not-leak"
          error  (try
                   (client/normalize-details {:catalog "lake"
                                              :base-url "not a URL"
                                              :username "alice"
                                              :password secret})
                   nil
                   (catch Exception e e))]
      (is (some? error))
      (is (re-find #"API URL" (ex-message error)))
      (is (not (re-find (re-pattern secret) (pr-str error)))))))

(deftest numeric-options-must-be-positive-test
  (doseq [[option value] [[:connect-timeout-seconds 0]
                          [:request-timeout-seconds -1]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"positive integer"
                          (client/normalize-details {:catalog "lake"
                                                     :username "alice"
                                                     :password "secret"
                                                     option value})))))

(deftest query-request-test
  (let [request (client/query-request {:catalog "lake"
                                       :schema "reporting"
                                       :username "alice"
                                       :password "secret"
                                       :compute-size "M"}
                                      {:query "SELECT 1"
                                       :max-rows 25
                                       :timezone "Europe/Paris"
                                       :session-id "metabase-session"
                                       :visible false
                                       :requested-by "metabase"})]
    (is (= "SELECT 1" (.statement request)))
    (is (= "lake" (.catalog request)))
    (is (= "reporting" (.schema request)))
    (is (= "metabase-session" (.sessionId request)))
    (is (= "M" (some-> request .computeSize .name)))
    (is (true? (.sanitize request)))
    (is (= 25 (.limit request)))
    (is (= "Europe/Paris" (.timezone request)))
    (is (false? (.visible request)))
    (is (= "metabase" (.requestedBy request)))
    (is (= "duckdb" (.dialect request)))))

(deftest query-request-compute-size-test
  (let [details {:catalog "lake" :username "alice" :password "secret" :compute-size "M"}]
    (testing "a query uses the size configured on the connection"
      (is (= "M" (some-> (client/query-request details {:query "SELECT 1"})
                         .computeSize .name))))

    (testing "the driver can size a statement whose cost it already knows"
      (is (= "XS" (some-> (client/query-request details {:query        "SELECT 1"
                                                        :compute-size :XS})
                          .computeSize .name))))))

(deftest metadata-statements-carry-the-driver-size-test
  (testing "the driver sizes its own statements rather than inheriting the query size"
    (let [details  {:catalog "lake" :username "alice" :password "secret" :compute-size "L"}
          captured (atom nil)]
      (with-redefs-fn {#'client/query-request (fn [_details request]
                                               (reset! captured request)
                                               (throw (ex-info "captured before any I/O" {})))}
        (fn []
          (doseq [[what call] [["schemas"  #(client/list-schemas! details)]
                               ["tables"   #(client/list-tables! details)]
                               ["fields"   #(client/describe-fields! details)]
                               ["timezone" #(client/default-timezone details)]]]
            (testing what
              (reset! captured nil)
              (is (thrown? clojure.lang.ExceptionInfo (call)))
              (is (= :XS (:compute-size @captured)))
              (is (true? (:ephemeral @captured))
                  "must stay ephemeral, or each statement leaves a session nothing reuses"))))))))

(deftest test-connection-validates-details-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Catalog is required"
                        (client/test-connection! {:username "alice"
                                                  :password "secret"}))))

(deftest normalize-details-preserves-schema-filters-test
  (is (= "inclusion"
         (:schema-filters-type
          (client/normalize-details {:catalog "lake"
                                     :username "alice"
                                     :password "secret"
                                     :schema-filters-type "inclusion"
                                     :schema-filters-patterns "main,analytics"}))))
  (is (= "main,analytics"
         (:schema-filters-patterns
          (client/sanitize-details {:catalog "lake"
                                    :username "alice"
                                    :password "secret"
                                    :schema-filters-type "inclusion"
                                    :schema-filters-patterns "main,analytics"})))))

(deftest list-tables-sql-ignores-default-schema-test
  (testing "default query schema must not restrict table discovery SQL"
    (let [sql (#'client/list-tables-sql {:catalog "memory" :schema "main"})]
      (is (re-find #"table_catalog = 'memory'" sql))
      (is (not (re-find #"table_schema = 'main'" sql)))
      (is (re-find #"BASE TABLE',\s*'VIEW'" sql)))))
