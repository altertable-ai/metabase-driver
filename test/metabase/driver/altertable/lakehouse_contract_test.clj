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
   [clojure.core.async :as async]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.driver.altertable.client :as client])
  (:import
   (com.fasterxml.jackson.databind JsonNode ObjectMapper)
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.net InetSocketAddress)
   (java.nio.charset StandardCharsets)
   (java.util UUID)
   (java.util.concurrent CountDownLatch Executors ExecutorService TimeUnit)))

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

(defn- metadata-line [statement rows-limit session-id]
  (str "{\"statement\":" (json-string statement)
       ",\"rows_limit\":" (or rows-limit "null")
       ",\"rows_offset\":" (if rows-limit 0 "null")
       ",\"init_time_ms\":1,\"connections_errors\":{}"
       ",\"session_id\":" (json-string session-id)
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

(defn- ndjson [statement rows-limit columns rows session-id]
  (str/join "\n" (concat [(metadata-line statement rows-limit session-id)
                          (schema-line columns)]
                         (map row-line rows)
                         [""])))

(defn- query-handler [{:keys [columns rows on-request on-cancel transform-response]} requests]
  (reify HttpHandler
    (^void handle [_ ^HttpExchange exchange]
      (let [body
            (if (= "DELETE" (.getRequestMethod exchange))
              (do (when on-cancel (on-cancel exchange))
                  "{\"cancelled\":true,\"message\":\"Cancelled\"}")
              (let [^JsonNode payload (.readTree object-mapper (.getRequestBody exchange))
                    session-id (or (.asText ^JsonNode (.path payload "session_id") nil) (str (UUID/randomUUID)))
                    statement  (.asText ^JsonNode (.get payload "statement"))
                    rows-limit (if (json-true? payload "sanitize")
                                 legacy-sanitize-rows-limit
                                 (json-number payload "limit"))]
                (swap! requests conj {:statement statement :rows-limit rows-limit :payload payload :response-session-id session-id})
                (when on-request (on-request payload))
                (if (str/starts-with? statement "DESCRIBE ")
                  (ndjson statement nil [["column_name" "VARCHAR"] ["column_type" "VARCHAR"]]
                          (mapv vec columns) session-id)
                  (ndjson statement rows-limit columns (cond->> rows rows-limit (take rows-limit)) session-id))))
            body (if (and transform-response (= "POST" (.getRequestMethod exchange)))
                   (transform-response body)
                   body)
            encoded (.getBytes ^String body StandardCharsets/UTF_8)]
        (.add (.getResponseHeaders exchange) "Content-Type" "application/x-ndjson")
        (.sendResponseHeaders exchange 200 (alength encoded))
        (with-open [out (.getResponseBody exchange)] (.write out encoded))
        nil))))

(defn- with-fake-lakehouse
  "Serve `dataset` from a throwaway endpoint and call `f` with connection details and an
  atom holding every request the driver sent."
  [dataset f]
  (let [requests (atom [])
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ^ExecutorService executor (Executors/newCachedThreadPool)]
    (.createContext server "/query" (query-handler dataset requests))
    (.setExecutor server executor)
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
        (.stop server 0)
        (.shutdownNow executor)))))

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

(deftest fixed-compute-queries-reuse-an-exclusive-session-test
  (with-fake-lakehouse
    {:columns [["n" "BIGINT"]] :rows [[42]]}
    (fn [details requests]
      (let [details (assoc details :compute-size "XS" :session-pool-size 1)
            query {:query "SELECT 42 AS n" :session-reuse? true}]
        (dotimes [_ 3]
          (is (= [[42]] (second (run-query! details query))))))
      (is (= [nil (:response-session-id (first @requests))
                  (:response-session-id (first @requests))]
             (mapv #(.asText ^JsonNode (.path ^JsonNode (:payload %) "session_id") nil) @requests)))
      (is (every? #(= "false" (.asText ^JsonNode (.path ^JsonNode (:payload %) "ephemeral")))
                  @requests)))))

(deftest session-reuse-is-explicit-and-fixed-compute-only-test
  (doseq [[settings query]
          [[{} {:query "SELECT 42 AS n" :session-reuse? true}]
           [{:compute-size "XS"} {:query "SELECT 42 AS n" :session-reuse? true}]
           [{:session-pool-size 1} {:query "SELECT 42 AS n" :session-reuse? true}]
           [{:compute-size "XS" :session-pool-size 1} {:query "SELECT 42 AS n"}]
           [{:compute-size "XS" :session-pool-size 1}
            {:query "SELECT 42 AS n" :session-reuse? true :ephemeral true}]]]
    (with-fake-lakehouse
      {:columns [["n" "BIGINT"]] :rows [[42]]}
      (fn [details requests]
        (dotimes [_ 2] (run-query! (merge details settings) query))
        (is (every? #(.isNull ^JsonNode (.path ^JsonNode (:payload %) "session_id"))
                    @requests))))))

(deftest failed-consumers-discard-their-session-test
  (with-fake-lakehouse
    {:columns [["n" "BIGINT"]] :rows [[42]]}
    (fn [details requests]
      (let [details (assoc details :compute-size "XS" :session-pool-size 1)
            query {:query "SELECT 42 AS n" :session-reuse? true}]
        (run-query! details query)
        (is (thrown-with-msg? Exception #"consumer failed"
                             (client/execute-query! details query nil
                               (fn [_ _] (throw (ex-info "consumer failed" {}))))))
        (run-query! details query))
      (is (.isNull ^JsonNode (.path ^JsonNode (:payload (last @requests)) "session_id"))))))


(defn- session-id [request]
  (.asText ^JsonNode (.path ^JsonNode (:payload request) "session_id") nil))

(deftest busy-pool-overflow-does-not-wait-or-share-a-session-test
  (with-fake-lakehouse
    {:columns [["n" "BIGINT"]] :rows [[42]]}
    (fn [details requests]
      (let [details (assoc details :compute-size "XS" :session-pool-size 1)
            query {:query "SELECT 42 AS n" :session-reuse? true}
            entered (promise)
            release (promise)
            first-query (future (client/execute-query! details query nil
                                  (fn [_ rows]
                                    (deliver entered true)
                                    (deref release 5000 false)
                                    (into [] rows))))]
        (try
          (is (= true (deref entered 2000 ::timeout)))
          (let [second-query (future (run-query! details query))]
            (is (= [[42]] (second (deref second-query 1000 [nil ::timeout])))))
          (is (= 2 (count @requests)))
          (is (every? nil? (map session-id @requests)))
          (finally (deliver release true)))
        (is (= [[42]] (deref first-query 2000 ::timeout)))
        (run-query! details query)
        (is (= (:response-session-id (first @requests)) (session-id (last @requests))))))))

(deftest concurrent-queries-retain-only-the-configured-number-of-sessions-test
  (with-fake-lakehouse
    {:columns [["n" "BIGINT"]] :rows [[42]]}
    (fn [details requests]
      (let [details (assoc details :compute-size "XS" :session-pool-size 4)
            query {:query "SELECT 42 AS n" :session-reuse? true}
            entered (CountDownLatch. 12)
            release (promise)
            queries (mapv (fn [_]
                            (future
                              (client/execute-query! details query nil
                                (fn [_ rows]
                                  (.countDown entered)
                                  (deref release 10000 false)
                                  (into [] rows)))))
                          (range 12))]
        (try
          (is (.await entered 5 TimeUnit/SECONDS))
          (is (= 12 (count @requests)))
          (is (every? nil? (map session-id @requests)))
          (finally (deliver release true)))
        (doseq [query queries] (is (= [[42]] (deref query 5000 ::timeout))))
        (let [retained (->> @requests
                            (filter #(= "false" (.asText ^JsonNode (.path ^JsonNode (:payload %) "ephemeral"))))
                            (map :response-session-id)
                            set)]
          (is (= 4 (count retained)))
          (dotimes [_ 4] (run-query! details query))
          (is (= retained (set (map session-id (drop 12 @requests))))))))))

(deftest empty-results-can-reuse-a-session-test
  (with-fake-lakehouse
    {:columns [["n" "BIGINT"]] :rows []}
    (fn [details requests]
      (let [details (assoc details :compute-size "XS" :session-pool-size 1)
            query {:query "SELECT 42 AS n WHERE false" :session-reuse? true}]
        (dotimes [_ 2]
          (let [[metadata rows] (run-query! details query)]
            (is (= [] rows))
            (is (= [:type/Integer] (mapv :base_type (:cols metadata))))))
        (is (= (:response-session-id (first @requests)) (session-id (last @requests))))))))

(deftest failure-before-metadata-does-not-lose-a-pool-slot-test
  (let [fail? (atom true)]
    (with-fake-lakehouse
      {:columns [["n" "BIGINT"]] :rows [[42]]
       :transform-response (fn [body] (if (compare-and-set! fail? true false) "not-json\n" body))}
      (fn [details requests]
        (let [details (assoc details :compute-size "XS" :session-pool-size 1)
              query {:query "SELECT 42 AS n" :session-reuse? true}]
          (is (thrown? Exception (run-query! details query)))
          (dotimes [_ 2] (is (= [[42]] (second (run-query! details query)))))
          (is (= [nil nil (:response-session-id (second @requests))]
                 (mapv session-id @requests))))))))

(deftest explicit-session-identifiers-bypass-the-pool-test
  (with-fake-lakehouse
    {:columns [["n" "BIGINT"]] :rows [[42]]}
    (fn [details requests]
      (let [details (assoc details :compute-size "XS" :session-pool-size 1)
            query {:query "SELECT 42 AS n" :session-reuse? true}
            explicit-id (str (UUID/randomUUID))]
        (run-query! details query)
        (run-query! details (assoc query :session-id explicit-id))
        (run-query! details query)
        (is (= [nil explicit-id (:response-session-id (first @requests))]
               (mapv session-id @requests)))))))


(deftest pool-scopes-isolate-effective-connection-settings-test
  (doseq [[detail-changes query-changes]
          [[{:database-id 2} {}] [{:catalog "other"} {}] [{:schema "other"} {}]
           [{:username "bob"} {}] [{:password "other"} {}] [{:compute-size "S"} {}]
           [{:request-timeout-seconds 10} {}] [{} {:timezone "Europe/Paris"}]]]
    (with-fake-lakehouse
      {:columns [["n" "BIGINT"]] :rows [[42]]}
      (fn [details requests]
        (let [details (assoc details :compute-size "XS" :session-pool-size 1 :database-id 1)
              query {:query "SELECT 42 AS n" :session-reuse? true}]
          (dotimes [_ 2] (run-query! details query))
          (dotimes [_ 2] (run-query! (merge details detail-changes) (merge query query-changes)))
          (is (= [nil (:response-session-id (first @requests))
                       nil (:response-session-id (nth @requests 2))]
                 (mapv session-id @requests))))))))

(deftest incomplete-streams-are-cancelled-and-discarded-test
  (doseq [consume [(fn [_] nil) (fn [rows] (reduce (fn [_ row] (reduced row)) nil rows))]]
    (let [cancellations (atom 0)]
      (with-fake-lakehouse
        {:columns [["n" "BIGINT"]] :rows (mapv vector (range 2000))
         :on-cancel (fn [_] (swap! cancellations inc))}
        (fn [details requests]
          (let [details (assoc details :compute-size "XS" :session-pool-size 1)
                query {:query "SELECT n FROM range(2000) t(n)" :session-reuse? true}]
            (run-query! details query)
            (client/execute-query! details query nil (fn [_ rows] (consume rows)))
            (is (= 1 @cancellations))
            (is (= 2000 (count (second (run-query! details query)))))
            (is (nil? (session-id (last @requests))))))))))

(deftest malformed-streams-are-not-replayed-or-reused-test
  (doseq [suffix ["not-json\n" "{\"error\":\"deliberate query failure\"}\n"]]
    (let [fail? (atom true)]
      (with-fake-lakehouse
        {:columns [["n" "BIGINT"]] :rows [[42]]
         :transform-response (fn [body] (if (compare-and-set! fail? true false) (str body suffix) body))}
        (fn [details requests]
          (let [details (assoc details :compute-size "XS" :session-pool-size 1)
                query {:query "SELECT 42 AS n" :session-reuse? true}]
            (is (thrown? Exception (run-query! details query)))
            (is (= 1 (count @requests)))
            (is (= [[42]] (second (run-query! details query))))
            (is (= [nil nil] (mapv session-id @requests)))))))))

(deftest replacement-session-identifiers-are-adopted-without-replaying-test
  (let [replacement (str (UUID/randomUUID))
        responses (atom 0)]
    (with-fake-lakehouse
      {:columns [["n" "BIGINT"]] :rows [[42]]
       :transform-response (fn [body]
                             (if (= 2 (swap! responses inc))
                               (str/replace body #"\"session_id\":\"[^\"]+\""
                                            (str "\"session_id\":\"" replacement "\""))
                               body))}
      (fn [details requests]
        (let [details (assoc details :compute-size "XS" :session-pool-size 1)
              query {:query "SELECT 42 AS n" :session-reuse? true}]
          (dotimes [_ 3] (is (= [[42]] (second (run-query! details query)))))
          (is (= 3 (count @requests)))
          (is (= replacement (session-id (last @requests)))))))))

(deftest late-cancellation-cannot-affect-a-later-session-borrower-test
  (let [cancel-entered (promise)
        cancel-release (promise)]
    (with-fake-lakehouse
      {:columns [["n" "BIGINT"]] :rows [[42]]
       :on-cancel (fn [_] (deliver cancel-entered true) (deref cancel-release 5000 false))}
      (fn [details requests]
        (let [details (assoc details :compute-size "XS" :session-pool-size 1)
              query {:query "SELECT 42 AS n" :session-reuse? true}
              cancel-chan (async/promise-chan)
              first-query (future (client/execute-query! details query cancel-chan
                                    (fn [_ rows]
                                      (let [result (into [] rows)]
                                        (async/>!! cancel-chan true)
                                        (deref cancel-entered 2000 false)
                                        result))))]
          (try
            (is (= true (deref cancel-entered 2000 ::timeout)))
            (is (= [[42]] (second (run-query! details query))))
            (is (not (realized? first-query)))
            (is (= [nil nil] (mapv session-id @requests)))
            (finally (deliver cancel-release true)))
          (is (= [[42]] (deref first-query 2000 ::timeout)))
          (run-query! details query)
          (is (nil? (session-id (last @requests))))
          (run-query! details query)
          (is (= (:response-session-id (nth @requests 2)) (session-id (last @requests)))))))))

(deftest independent-query-cleanup-does-not-wait-for-cancellation-acknowledgement-test
  (doseq [pool-size [0 1]]
    (testing (if (zero? pool-size) "reuse disabled" "configured pool is busy")
      (let [occupied (promise)
            release-occupant (promise)
            cancel-entered (promise)
            cancel-release (promise)]
        (with-fake-lakehouse
          {:columns [["n" "BIGINT"]] :rows [[42]]
           :on-cancel (fn [_] (deliver cancel-entered true) @cancel-release)}
          (fn [details _requests]
            (let [details (assoc details :compute-size "XS" :session-pool-size pool-size)
                  native-query {:query "SELECT 42 AS n" :session-reuse? true}
                  occupant (future (client/execute-query! details native-query nil
                                     (fn [_ rows]
                                       (deliver occupied true)
                                       @release-occupant
                                       (into [] rows))))]
              (try
                (is (= true (deref occupied 5000 ::timeout)))
                (let [cancel-chan (async/promise-chan)
                      query (future (client/execute-query! details native-query cancel-chan
                                      (fn [_ rows]
                                        (let [result (into [] rows)]
                                          (async/>!! cancel-chan true)
                                          (deref cancel-entered 5000 false)
                                          result))))]
                  (try
                    (is (= true (deref cancel-entered 5000 ::timeout)))
                    (is (= [[42]] (deref query 2000 ::timeout)))
                    (finally (deliver cancel-release true)))
                  (deref query 5000 ::timeout))
                (finally (deliver release-occupant true)))
              (is (= [[42]] (deref occupant 5000 ::timeout))))))))))

(deftest invalidation-does-not-return-an-active-session-to-the-new-pool-test
  (with-fake-lakehouse
    {:columns [["n" "BIGINT"]] :rows [[42]]}
    (fn [details requests]
      (let [details (assoc details :compute-size "XS" :session-pool-size 1)
            query {:query "SELECT 42 AS n" :session-reuse? true}
            entered (promise)
            release (promise)
            first-query (future (client/execute-query! details query nil
                                  (fn [_ rows]
                                    (deliver entered true)
                                    (deref release 5000 false)
                                    (into [] rows))))]
        (try
          (is (= true (deref entered 2000 ::timeout)))
          (client/forget-clients!)
          (is (= [[42]] (second (run-query! details query))))
          (finally (deliver release true)))
        (is (= [[42]] (deref first-query 2000 ::timeout)))
        (run-query! details query)
        (is (= (:response-session-id (nth @requests 1)) (session-id (last @requests))))))))
