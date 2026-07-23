(ns metabase.driver.altertable.query-processor-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.altertable]
   [metabase.driver.sql.query-processor :as sql.qp]))

(defn- format-sql [honeysql-form]
  (driver/with-driver :altertable
    (sql.qp/format-honeysql :altertable honeysql-form)))

(deftest format-honeysql-inlines-parameters-test
  (testing "literal values are inlined so the Lakehouse API receives no bind params"
    (let [[sql & params] (format-sql {:select [[:*]]
                                      :from   [[:events]]
                                      :where  [:and
                                               [:= :id 7]
                                               [:= :label "O'Reilly"]
                                               [:= :active true]]})]
      (is (string? sql))
      (is (empty? params)
          "executable Altertable SQL must not carry JDBC-style bind parameters")
      (is (not (str/includes? sql "?"))
          "executable Altertable SQL must not contain unbound placeholders")
      (is (str/includes? sql "7"))
      (is (re-find #"O''Reilly|O\\\\'Reilly" sql)))))

(deftest date-truncation-units-test
  (driver/with-driver :altertable
    (doseq [[unit expected-fragment]
            [[:minute "date_trunc"]
             [:hour "date_trunc"]
             [:day "date_trunc"]
             [:week "date_trunc"]
             [:month "date_trunc"]
             [:quarter "date_trunc"]
             [:year "date_trunc"]]]
      (testing unit
        (let [form (sql.qp/date :altertable unit :created_at)
              [sql & params] (format-sql {:select [[form :bucket]]})]
          (is (empty? params))
          (is (str/includes? (str/lower-case sql) expected-fragment)
              (str "expected " expected-fragment " in " sql)))))))

(deftest date-extract-units-test
  (driver/with-driver :altertable
    (doseq [[unit expected-fragment]
            [[:minute-of-hour "minute"]
             [:hour-of-day "hour"]
             [:day-of-month "day"]
             [:day-of-year "dayofyear"]
             [:day-of-week "isodow"]
             [:month-of-year "month"]
             [:quarter-of-year "quarter"]]]
      (testing unit
        (let [form (sql.qp/date :altertable unit :created_at)
              [sql & params] (format-sql {:select [[form :part]]})]
          (is (empty? params))
          (is (str/includes? (str/lower-case sql) expected-fragment)
              (str "expected " expected-fragment " in " sql)))))))

(deftest add-interval-honeysql-form-test
  (driver/with-driver :altertable
    (let [form (sql.qp/add-interval-honeysql-form :altertable :created_at 3 :day)
          [sql & params] (format-sql {:select [[form :shifted]]})]
      (is (empty? params))
      (is (re-find #"(?i)interval\s+'3'\s+day" sql)))
    (let [form (sql.qp/add-interval-honeysql-form :altertable :created_at 1 :quarter)
          [sql & params] (format-sql {:select [[form :shifted]]})]
      (is (empty? params))
      (is (re-find #"(?i)interval\s+'3'\s+month" sql)
          "quarters should expand to 3 months"))))

(deftest datetime-diff-units-test
  (driver/with-driver :altertable
    (doseq [unit [:year :quarter :month :week :day :hour :minute :second]]
      (testing unit
        (let [form (sql.qp/datetime-diff :altertable unit :start_at :end_at)
              [sql & params] (format-sql {:select [[form :diff]]})]
          (is (empty? params))
          (is (str/includes? (str/lower-case sql) "datesub")
              (str "expected datesub in " sql)))))))

(deftest unix-timestamp-seconds-test
  (driver/with-driver :altertable
    (let [form (sql.qp/unix-timestamp->honeysql :altertable :seconds :epoch)
          [sql & params] (format-sql {:select [[form :ts]]})]
      (is (empty? params))
      (is (str/includes? (str/lower-case sql) "to_timestamp")))))

(deftest regex-match-first-test
  (driver/with-driver :altertable
    (let [form (sql.qp/->honeysql :altertable [:regex-match-first :label "a+"])
          [sql & params] (format-sql {:select [[form :match]]})]
      (is (empty? params))
      (is (str/includes? (str/lower-case sql) "regexp_extract")))))

(deftest db-start-of-week-test
  (is (= :monday (driver/db-start-of-week :altertable))))

(deftest aggregation-and-join-compile-smoke-test
  (testing "generic :sql HoneySQL forms still format cleanly for Altertable"
    (let [[sql & params] (format-sql
                          {:select   [[[:count :*] :count]
                                      [[:%stddev.amount] :stddev]
                                      [[:percentile_cont :amount] :p50]]
                           :from     [[:orders :orders]]
                           :left-join [[:people :people] [:= :orders.user_id :people.id]]
                           :where    [:= :orders.status [:inline "complete"]]
                           :group-by [:orders.status]})]
      (is (empty? params))
      (is (not (str/includes? sql "?")))
      (is (str/includes? (str/lower-case sql) "left join"))
      (is (str/includes? (str/lower-case sql) "count")))))
