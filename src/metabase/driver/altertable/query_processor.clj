(ns metabase.driver.altertable.query-processor
  "DuckDB-compatible HoneySQL overrides for the Altertable Metabase driver.

  Values are inlined because the Lakehouse API accepts SQL text without a
  separate bind-parameter collection."
  (:require
   [metabase.driver :as driver]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.honey-sql-2 :as h2x]))

(set! *warn-on-reflection* true)

(defmethod sql.qp/format-honeysql :altertable
  [driver honeysql-form]
  (binding [driver/*compile-with-inline-parameters* true]
    ((get-method sql.qp/format-honeysql :sql) driver honeysql-form)))

(defmethod driver/db-start-of-week :altertable
  [_]
  :monday)

(defmethod sql.qp/add-interval-honeysql-form :altertable
  [driver hsql-form amount unit]
  (if (= unit :quarter)
    (recur driver hsql-form (* amount 3) :month)
    (h2x/+ (h2x/->timestamp-with-time-zone hsql-form)
           [:raw (format "(INTERVAL '%d' %s)" (int amount) (name unit))])))

(defmethod sql.qp/date [:altertable :default]         [_ _ expr] expr)
(defmethod sql.qp/date [:altertable :minute]          [_ _ expr] [:date_trunc (h2x/literal :minute) expr])
(defmethod sql.qp/date [:altertable :minute-of-hour]  [_ _ expr] [:minute expr])
(defmethod sql.qp/date [:altertable :hour]            [_ _ expr] [:date_trunc (h2x/literal :hour) expr])
(defmethod sql.qp/date [:altertable :hour-of-day]     [_ _ expr] [:hour expr])
(defmethod sql.qp/date [:altertable :day]             [_ _ expr] [:date_trunc (h2x/literal :day) expr])
(defmethod sql.qp/date [:altertable :day-of-month]    [_ _ expr] [:day expr])
(defmethod sql.qp/date [:altertable :day-of-year]     [_ _ expr] [:dayofyear expr])

(defmethod sql.qp/date [:altertable :day-of-week]
  [driver _ expr]
  (sql.qp/adjust-day-of-week driver [:isodow expr]))

(defmethod sql.qp/date [:altertable :week]
  [driver _ expr]
  (sql.qp/adjust-start-of-week driver (partial conj [:date_trunc] (h2x/literal :week)) expr))

(defmethod sql.qp/date [:altertable :month]           [_ _ expr] [:date_trunc (h2x/literal :month) expr])
(defmethod sql.qp/date [:altertable :month-of-year]   [_ _ expr] [:month expr])
(defmethod sql.qp/date [:altertable :quarter]         [_ _ expr] [:date_trunc (h2x/literal :quarter) expr])
(defmethod sql.qp/date [:altertable :quarter-of-year] [_ _ expr] [:quarter expr])
(defmethod sql.qp/date [:altertable :year]            [_ _ expr] [:date_trunc (h2x/literal :year) expr])

(defmethod sql.qp/datetime-diff [:altertable :year]
  [_driver _unit x y]
  [:datesub (h2x/literal :year) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:altertable :quarter]
  [_driver _unit x y]
  [:datesub (h2x/literal :quarter) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:altertable :month]
  [_driver _unit x y]
  [:datesub (h2x/literal :month) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:altertable :week]
  [_driver _unit x y]
  (h2x// [:datesub (h2x/literal :day) (h2x/cast "date" x) (h2x/cast "date" y)] 7))

(defmethod sql.qp/datetime-diff [:altertable :day]
  [_driver _unit x y]
  [:datesub (h2x/literal :day) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:altertable :hour]
  [_driver _unit x y]
  [:datesub (h2x/literal :hour) x y])

(defmethod sql.qp/datetime-diff [:altertable :minute]
  [_driver _unit x y]
  [:datesub (h2x/literal :minute) x y])

(defmethod sql.qp/datetime-diff [:altertable :second]
  [_driver _unit x y]
  [:datesub (h2x/literal :second) x y])

(defmethod sql.qp/unix-timestamp->honeysql [:altertable :seconds]
  [_ _ expr]
  [:to_timestamp (h2x/cast :DOUBLE expr)])

(defmethod sql.qp/->honeysql [:altertable :regex-match-first]
  [driver [_ arg pattern]]
  [:regexp_extract (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver pattern)])
