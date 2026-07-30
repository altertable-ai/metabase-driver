(ns metabase.driver.altertable.connection-perf
  "Reports what the driver costs per query against a running Altertable mock: round trips,
  TCP connections and threads. Traffic is proxied through a counting socket because those
  counts are stable, while wall clock drifts as the mock warms up.

  With the mock running:

    clojure -M:test -m metabase.driver.altertable.connection-perf"
  (:require
   [metabase.driver.altertable.client :as client])
  (:import
   (java.io InputStream OutputStream)
   (java.lang.management ManagementFactory)
   (java.net ServerSocket Socket)))

(set! *warn-on-reflection* true)

(def ^:private ^String upstream-host "127.0.0.1")
(def ^:private upstream-port 15000)
(def ^:private proxy-port 15100)

(def ^:private requests (atom 0))
(def ^:private connections (atom 0))

(defn- count-requests!
  "Count request lines in one read. A request split across two reads would be missed, which
  has not happened at these sizes."
  [^bytes buffer length]
  (let [chunk (String. buffer 0 (int length) "ISO-8859-1")]
    (swap! requests + (count (re-seq #"POST /" chunk)))))

(defn- pump! [^InputStream in ^OutputStream out count?]
  (let [buffer (byte-array 32768)]
    (try
      (loop []
        (let [length (.read in buffer)]
          (when (pos? length)
            (when count? (count-requests! buffer length))
            (.write out buffer 0 length)
            (.flush out)
            (recur))))
      (catch Exception _ nil))
    (try (.close out) (catch Exception _ nil))))

(defn- start-proxy!
  "Forward `proxy-port` to the mock, counting requests and accepted connections."
  ^ServerSocket []
  (let [server (ServerSocket. proxy-port)]
    (doto (Thread.
           (fn []
             (try
               (loop []
                 (let [downstream (.accept server)
                       upstream   (Socket. upstream-host (int upstream-port))]
                   (swap! connections inc)
                   (.start (Thread. #(pump! (.getInputStream downstream)
                                            (.getOutputStream upstream) true)))
                   (.start (Thread. #(pump! (.getInputStream upstream)
                                            (.getOutputStream downstream) false)))
                   (recur)))
               (catch Exception _ nil))))
      (.setDaemon true)
      (.start))
    server))

(def ^:private details
  {:base-url                (str "http://" upstream-host ":" proxy-port)
   :catalog                 "memory"
   :schema                  "main"
   :username                "testuser"
   :password                "testpass"
   :request-timeout-seconds 60})

(defn- run-query!
  "Execute `sql` and reduce every row, returning the row count."
  [sql]
  (let [rows (volatile! nil)]
    (client/execute-query! details {:query sql} nil
                           (fn [_metadata reducible]
                             (vreset! rows (reduce (fn [n _] (inc n)) 0 reducible))))
    @rows))

(defn- live-threads []
  (.getThreadCount (ManagementFactory/getThreadMXBean)))

(defn- report! [label thunk]
  (reset! requests 0)
  (reset! connections 0)
  (let [threads-before (live-threads)
        started        (System/nanoTime)
        _              (thunk)
        elapsed-ms     (/ (- (System/nanoTime) started) 1e6)]
    (println (format "%-38s %8.1f ms  requests=%-4d connections=%-4d threads%+d"
                     label elapsed-ms @requests @connections
                     (- (live-threads) threads-before)))))

(def ^:private row-count 50000)

(defn -main [& _args]
  (start-proxy!)
  (Thread/sleep 300)
  (run-query! (format "CREATE OR REPLACE TABLE perf_rows AS
                       SELECT i AS id, 'name-' || i AS name, i * 1.5 AS amount,
                              TIMESTAMP '2026-01-01' + INTERVAL (i) SECOND AS created_at
                       FROM range(%d) t(i)" row-count))

  (println "\nper-query overhead")
  (report! "20x one saved question"
           #(dotimes [_ 20] (run-query! "SELECT * FROM perf_rows LIMIT 100")))
  (report! "20x a different statement each"
           #(dotimes [i 20]
              (run-query! (format "SELECT id, name FROM perf_rows WHERE id > %d LIMIT 100" i))))

  (println "\nmetadata and sync")
  (report! "list-schemas!"        #(client/list-schemas! details))
  (report! "list-tables!"         #(client/list-tables! details))
  (report! "describe-fields!"     #(client/describe-fields! details))
  (report! "5x describe-table!"   #(dotimes [_ 5] (client/describe-table! details
                                                                          {:name   "perf_rows"
                                                                           :schema "main"})))
  (report! "5x native metadata"   #(dotimes [_ 5] (client/query-result-metadata
                                                   details "SELECT * FROM perf_rows")))

  (println "\nclient construction, no I/O")
  (report! "200x details->client" #(dotimes [_ 200] (client/details->client details)))

  (println (format "\nrow streaming (%d rows; decoding is SDK-bound, not driver-bound)" row-count))
  (report! "read every row" #(run-query! "SELECT * FROM perf_rows"))
  (report! "read every row again" #(run-query! "SELECT * FROM perf_rows"))

  (shutdown-agents)
  (System/exit 0))
