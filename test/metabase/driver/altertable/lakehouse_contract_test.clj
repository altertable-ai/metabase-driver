(ns metabase.driver.altertable.lakehouse-contract-test
  "Drives the driver against a `POST /query` endpoint that answers the way the Lakehouse
  API documents.

  Parts of that contract decide whether a question is answered correctly and are not
  implemented by altertable-mock, so no other test here can see them. The legacy
  `sanitize` flag is one: it makes the server throw away the requested pagination in
  favour of a fixed 500-row window. The schema line is the other: it names columns with
  `{name, type}` objects, where the mock sends bare strings. Both failures are silent,
  which is what makes them worth a test."
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.driver.altertable.client :as client])
  (:import
   (com.fasterxml.jackson.databind JsonNode ObjectMapper)
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.net InetSocketAddress)
   (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def ^:private legacy-sanitize-rows-limit
  "The window `POST /query` forces on any request that sets `sanitize`, discarding the
  limit that request asked for."
  500)

(def ^:private ^ObjectMapper object-mapper (ObjectMapper.))

(defn- json-string [value]
  (.writeValueAsString object-mapper (str value)))

(defn- json-number [^JsonNode payload ^String field]
  (let [^JsonNode node (.get payload field)]
    (when (and node (.isNumber node))
      (.asLong node))))

(defn- json-true? [^JsonNode payload ^String field]
  (let [^JsonNode node (.get payload field)]
    (boolean (and node (.isBoolean node) (.booleanValue node)))))

(defn- metadata-line [statement rows-limit]
  (str "{\"statement\":" (json-string statement)
       ",\"rows_limit\":" (or rows-limit "null")
       ",\"rows_offset\":" (if rows-limit 0 "null")
       ",\"init_time_ms\":1,\"connections_errors\":{}"
       ",\"session_id\":\"00000000-0000-0000-0000-000000000001\""
       ",\"query_id\":\"00000000-0000-0000-0000-000000000002\""
       ",\"worker_slug\":\"fake-lakehouse\"}"))

(defn- schema-line
  "The documented shape: a `{name, type}` object per column, never a bare string."
  [columns]
  (str "["
       (str/join "," (map (fn [[column-name database-type]]
                            (str "{\"name\":" (json-string column-name)
                                 ",\"type\":" (json-string database-type) "}"))
                          columns))
       "]"))

(defn- row-line [row]
  (str "["
       (str/join "," (map #(if (number? %) (str %) (json-string %)) row))
       "]"))

(defn- ndjson [statement rows-limit columns rows]
  (str/join "\n" (concat [(metadata-line statement rows-limit)
                          (schema-line columns)]
                         (map row-line rows)
                         [""])))

(defn- query-handler [{:keys [columns rows]} requests]
  (reify HttpHandler
    (^void handle [_ ^HttpExchange exchange]
      (let [^JsonNode payload (.readTree object-mapper (.getRequestBody exchange))
            statement  (.asText ^JsonNode (.get payload "statement"))
            rows-limit (if (json-true? payload "sanitize")
                         legacy-sanitize-rows-limit
                         (json-number payload "limit"))
            body       (if (str/starts-with? statement "DESCRIBE ")
                         (ndjson statement nil
                                 [["column_name" "VARCHAR"] ["column_type" "VARCHAR"]]
                                 (mapv vec columns))
                         (ndjson statement rows-limit columns
                                 (cond->> rows rows-limit (take rows-limit))))
            encoded    (.getBytes ^String body StandardCharsets/UTF_8)]
        (swap! requests conj {:statement statement :rows-limit rows-limit})
        (.add (.getResponseHeaders exchange) "Content-Type" "application/x-ndjson")
        (.sendResponseHeaders exchange 200 (alength encoded))
        (with-open [out (.getResponseBody exchange)]
          (.write out encoded))
        nil))))

(defn- with-fake-lakehouse
  "Serve `dataset` from a throwaway endpoint and call `f` with connection details and an
  atom holding every request the driver sent."
  [dataset f]
  (let [requests (atom [])
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/query" (query-handler dataset requests))
    (.start server)
    (try
      (f {:base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
          :catalog  "lake"
          :schema   "main"
          :username "alice"
          :password "secret"}
         requests)
      (finally
        (client/forget-clients!)
        (.stop server 0)))))

(defn- run-query!
  "Execute `native-query` and return `[metadata rows]`, failing rather than hanging."
  [details native-query]
  (let [response (promise)]
    (client/execute-query! details native-query nil
                           (fn [metadata rows]
                             (deliver response [metadata (into [] rows)])))
    (let [result (deref response 30000 ::timed-out)]
      (is (not= ::timed-out result) "the driver never responded")
      (when-not (= ::timed-out result)
        result))))

(deftest results-are-not-capped-at-the-legacy-window-test
  (testing "a result larger than the server's legacy 500-row window arrives whole"
    (let [row-count 2000]
      (with-fake-lakehouse
        {:columns [["n" "BIGINT"]]
         :rows    (mapv vector (range row-count))}
        (fn [details requests]
          (let [[_metadata rows] (run-query! details {:query "SELECT n FROM range(2000) t(n)"})]
            (is (= row-count (count rows))
                "truncation is silent, so a short result is the only symptom")
            (is (= [(dec row-count)] (last rows))
                "the tail is what a truncated result drops")
            (is (every? nil? (map :rows-limit @requests))
                "no window may be imposed beyond the one Metabase asked for")))))))

(deftest metabase-row-limit-is-the-only-row-limit-test
  (testing "the limit Metabase computed is the limit the server applies"
    (with-fake-lakehouse
      {:columns [["n" "BIGINT"]]
       :rows    (mapv vector (range 2000))}
      (fn [details requests]
        (let [[_metadata rows] (run-query! details {:query "SELECT n FROM range(2000) t(n)"
                                                   :max-rows 1200})]
          (is (= 1200 (count rows)))
          (is (some #{1200} (map :rows-limit @requests))
              "a limit above 500 must survive the request unchanged"))))))

(deftest column-names-reach-metabase-test
  (testing "visualization settings bind by column name, so names must not arrive blank"
    (with-fake-lakehouse
      {:columns [["booking_date" "DATE"]
                 ["GBV" "DECIMAL(18,2)"]
                 ["nb_bookings" "BIGINT"]]
       :rows    [["2026-01-05" 1250.75 12]]}
      (fn [details _requests]
        (let [[metadata _rows] (run-query!
                                details
                                {:query (str "SELECT booking_date, GBV, nb_bookings "
                                             "FROM bookings")})]
          (is (= ["booking_date" "GBV" "nb_bookings"]
                 (mapv :name (:cols metadata)))
              "blank names get uniquified into \"\", \"_2\", \"_3\" and stop matching settings")
          (is (= [:type/Date :type/Decimal :type/Integer]
                 (mapv :base_type (:cols metadata)))))))))
