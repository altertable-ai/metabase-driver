(ns metabase.driver.altertable.results
  (:require
   [clojure.string :as str])
  (:import
   (com.fasterxml.jackson.databind JsonNode)
   (java.math BigDecimal BigInteger)
   (java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime)
   (java.util Iterator Map$Entry UUID)))

(set! *warn-on-reflection* true)

(declare json-node->value)

(defn- object-node->map [^JsonNode node]
  (into {}
        (map (fn [^Map$Entry entry]
               [(.getKey entry) (json-node->value (.getValue entry))]))
        (iterator-seq (.fields node))))

(defn json-node->value
  "Convert a Jackson value to an ordinary JVM/Clojure value without narrowing
  integers or converting decimals through double precision."
  [^JsonNode node]
  (cond
    (or (nil? node) (.isNull node)) nil
    (.isBoolean node) (.booleanValue node)
    (.isIntegralNumber node) (if (.canConvertToLong node)
                               (.longValue node)
                               (.bigIntegerValue node))
    (.isFloatingPointNumber node) (.decimalValue node)
    (.isTextual node) (.textValue node)
    (.isArray node) (mapv json-node->value node)
    (.isObject node) (object-node->map node)
    (.isBinary node) (.binaryValue node)
    :else (.asText node)))

(defn database-type->base-type
  "Map Altertable's DuckDB-compatible database type names to Metabase types."
  [database-type]
  (let [database-type (some-> database-type str str/trim str/upper-case)]
    (cond
      (str/blank? database-type) :type/*
      (or (str/ends-with? database-type "[]")
          (re-find #"^(ARRAY|LIST)\b" database-type)) :type/Array
      (re-find #"^(STRUCT|MAP|UNION)\b" database-type) :type/Dictionary
      (or (= database-type "TIMESTAMPTZ")
          (re-find #"^TIMESTAMP.*WITH TIME ZONE" database-type)) :type/DateTimeWithTZ
      (or (= database-type "TIMETZ")
          (re-find #"^TIME.*WITH TIME ZONE" database-type)) :type/TimeWithTZ
      (re-find #"^TIMESTAMP(?:\b|_)" database-type) :type/DateTime
      (re-find #"^TIME(?:\b|_)" database-type) :type/Time
      (= database-type "DATE") :type/Date
      (re-find #"^(DECIMAL|NUMERIC)\b" database-type) :type/Decimal
      (re-find #"^(REAL|FLOAT|DOUBLE)\b" database-type) :type/Float
      (re-find #"^(TINYINT|SMALLINT|INTEGER|INT|BIGINT|HUGEINT|UTINYINT|USMALLINT|UINTEGER|UBIGINT|UHUGEINT)\b"
               database-type) :type/Integer
      (re-find #"^(BOOLEAN|BOOL)\b" database-type) :type/Boolean
      (#{"JSON" "VARIANT"} database-type) :type/JSON
      (= database-type "UUID") :type/UUID
      (re-find #"^(VARCHAR|CHAR|BPCHAR|TEXT|STRING|ENUM)\b" database-type) :type/Text
      :else :type/*)))

(defn- value->base-type [value]
  (cond
    (nil? value) nil
    (boolean? value) :type/Boolean
    (or (integer? value) (instance? BigInteger value)) :type/Integer
    (instance? BigDecimal value) :type/Decimal
    (or (float? value) (double? value)) :type/Float
    (string? value) :type/Text
    (instance? LocalDate value) :type/Date
    (instance? LocalTime value) :type/Time
    (instance? OffsetTime value) :type/TimeWithTZ
    (instance? LocalDateTime value) :type/DateTime
    (or (instance? OffsetDateTime value)
        (instance? ZonedDateTime value)) :type/DateTimeWithTZ
    (instance? UUID value) :type/UUID
    (map? value) :type/Dictionary
    (sequential? value) :type/Array
    :else :type/*))

(defn- widen-types [types]
  (let [types (disj (set types) nil)]
    (cond
      (empty? types) :type/*
      (= 1 (count types)) (first types)
      (every? #{:type/Integer :type/Decimal} types) :type/Decimal
      (every? #{:type/Integer :type/Decimal :type/Float} types) :type/Float
      :else :type/*)))

(defn infer-column-metadata
  "Infer native-query column metadata from a small prefix of converted rows."
  [column-names rows]
  {:cols
   (mapv (fn [index column-name]
           (let [base-type (widen-types
                            (map #(value->base-type (nth % index nil)) rows))]
             {:name           column-name
              :base_type      base-type
              :effective_type base-type}))
         (range)
         column-names)})

(defn column-metadata
  "Return execution metadata from described columns, falling back to row inference.

  Names come from the executed statement, which is what the result rows are actually
  labelled with. A blank name falls back to the described one: Metabase binds
  visualization settings by column name, so an unnamed column silently drops the series
  colors, axis labels and formatting configured against it."
  [column-names rows described-columns]
  (if (= (count column-names) (count described-columns))
    {:cols
     (mapv (fn [column-name {described-name :name :keys [database-type base-type]}]
             {:name           (or (not-empty column-name) described-name)
              :database_type  database-type
              :base_type      base-type
              :effective_type base-type})
           column-names
           described-columns)}
    (infer-column-metadata column-names rows)))

(defn rows-reducible
  "Return a single-use reducible that emits `buffered-rows`, then `remaining`,
  and always closes `closeable` when reduction ends or fails."
  [buffered-rows ^Iterator remaining ^java.lang.AutoCloseable closeable]
  (reify clojure.lang.IReduceInit
    (reduce [_ rf init]
      (try
        (reduce rf init (concat buffered-rows (iterator-seq remaining)))
        (finally
          (.close closeable))))))
