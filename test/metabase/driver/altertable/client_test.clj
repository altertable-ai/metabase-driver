(ns metabase.driver.altertable.client-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.altertable.client :as client]))

(deftest normalize-details-test
  (testing "normalizes defaults and basic credentials"
    (is (= {:base-url               "https://api.altertable.ai"
            :catalog                "analytics"
            :schema                 nil
            :username               "alice"
            :password               "secret"
            :basic-token            nil
            :compute-size           :AUTO
            :connect-timeout-seconds 5
            :request-timeout-seconds 60}
           (client/normalize-details {:catalog " analytics "
                                      :username "alice"
                                      :password "secret"}))))

  (testing "accepts token authentication and trims an optional schema"
    (is (= "reporting"
           (:schema (client/normalize-details {:catalog "lake"
                                               :schema " reporting "
                                               :basic-token "opaque"})))))

  (testing "requires a catalog"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Catalog is required"
                          (client/normalize-details {:basic-token "opaque"}))))

  (testing "requires one complete authentication method"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username and password"
                          (client/normalize-details {:catalog "lake"
                                                     :username "alice"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"credentials or a Basic token"
                          (client/normalize-details {:catalog "lake"}))))

  (testing "rejects malformed URLs without leaking secrets"
    (let [secret "do-not-leak"
          error  (try
                   (client/normalize-details {:catalog "lake"
                                              :base-url "not a URL"
                                              :basic-token secret})
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
                                                     :basic-token "opaque"
                                                     option value})))))

(deftest query-request-test
  (let [request (client/query-request {:catalog "lake"
                                       :schema "reporting"
                                       :basic-token "opaque"
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
