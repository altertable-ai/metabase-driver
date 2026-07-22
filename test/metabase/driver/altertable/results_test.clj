(ns metabase.driver.altertable.results-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.altertable.results :as results])
  (:import
   (com.fasterxml.jackson.databind ObjectMapper)
   (java.math BigDecimal BigInteger)
   (java.util ArrayList)))

(def ^:private mapper (ObjectMapper.))

(defn- json-node [value]
  (.readTree mapper ^String value))

(deftest json-node->value-test
  (is (nil? (results/json-node->value (json-node "null"))))
  (is (true? (results/json-node->value (json-node "true"))))
  (is (= 42 (results/json-node->value (json-node "42"))))
  (is (= (BigInteger. "9223372036854775808")
         (results/json-node->value (json-node "9223372036854775808"))))
  (is (= (BigDecimal. "1.25")
         (results/json-node->value (json-node "1.25"))))
  (is (= "hello" (results/json-node->value (json-node "\"hello\""))))
  (is (= [1 nil "x"]
         (results/json-node->value (json-node "[1,null,\"x\"]"))))
  (is (= {"a" 1, "nested" {"ok" true}}
         (results/json-node->value (json-node "{\"a\":1,\"nested\":{\"ok\":true}}")))))

(deftest database-type->base-type-test
  (doseq [[database-type expected]
          [["BOOLEAN" :type/Boolean]
           ["BIGINT" :type/Integer]
           ["DECIMAL(18,2)" :type/Decimal]
           ["DOUBLE" :type/Float]
           ["VARCHAR" :type/Text]
           ["DATE" :type/Date]
           ["TIME WITH TIME ZONE" :type/TimeWithTZ]
           ["TIMESTAMP" :type/DateTime]
           ["TIMESTAMPTZ" :type/DateTimeWithTZ]
           ["JSON" :type/JSON]
           ["INTEGER[]" :type/Array]
           ["STRUCT(name VARCHAR)" :type/Dictionary]
           ["MAP(VARCHAR, INTEGER)" :type/Dictionary]
           ["UUID" :type/UUID]
           ["BLOB" :type/*]
           ["SOMETHING_NEW" :type/*]]]
    (is (= expected (results/database-type->base-type database-type)) database-type)))

(deftest infer-column-metadata-test
  (is (= {:cols [{:name "id" :base_type :type/Integer :effective_type :type/Integer}
                  {:name "amount" :base_type :type/Decimal :effective_type :type/Decimal}
                  {:name "missing" :base_type :type/* :effective_type :type/*}]}
         (results/infer-column-metadata
          ["id" "amount" "missing"]
          [[1 2 nil]
           [2 (BigDecimal. "3.5") nil]])))
  (is (= :type/*
         (get-in (results/infer-column-metadata ["empty"] []) [:cols 0 :base_type])))
  (is (= :type/*
         (get-in (results/infer-column-metadata ["mixed"] [[1] ["one"]]) [:cols 0 :base_type]))))

(deftest rows-reducible-closes-resource-test
  (let [closed?   (atom false)
        remaining (.iterator (ArrayList. [[3] [4]]))
        closeable (reify java.lang.AutoCloseable
                    (close [_] (reset! closed? true)))
        rows      (results/rows-reducible [[1] [2]] remaining closeable)]
    (is (= [[1] [2] [3] [4]] (into [] rows)))
    (is @closed?)))
