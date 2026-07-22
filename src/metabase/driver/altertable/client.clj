(ns metabase.driver.altertable.client
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [metabase.driver.altertable.results :as results])
  (:import
   (ai.altertable.lakehouse LakehouseClient LakehouseClient$ComputeSize LakehouseClient$Config
                            LakehouseClient$LakehouseException LakehouseClient$QueryRequest
                            LakehouseClient$QueryResult)
   (com.fasterxml.jackson.databind JsonNode)
   (java.net URI)
   (java.time Duration)))

(set! *warn-on-reflection* true)

(def ^:private default-base-url "https://api.altertable.ai")
(def ^:private default-connect-timeout-seconds 5)
(def ^:private default-request-timeout-seconds 60)
(def ^:private compute-sizes #{:AUTO :XS :S :M :L :XL})

(defn- non-blank [value]
  (when (some? value)
    (not-empty (str/trim (str value)))))

(defn- invalid! [message field]
  (throw (ex-info message {:field field})))

(defn- valid-base-url [value]
  (let [value (or (non-blank value) default-base-url)]
    (try
      (let [uri (URI/create value)]
        (when-not (and (#{"http" "https"} (.getScheme uri))
                       (non-blank (.getHost uri)))
          (invalid! "API URL must be an absolute HTTP or HTTPS URL." :base-url))
        (str/replace value #"/+$" ""))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception _
        (invalid! "API URL must be an absolute HTTP or HTTPS URL." :base-url)))))

(defn- positive-integer [value default-value field]
  (let [parsed (cond
                 (nil? value) default-value
                 (integer? value) value
                 (string? value) (try
                                   (Long/parseLong (str/trim value))
                                   (catch NumberFormatException _ nil))
                 :else nil)]
    (when-not (and (integer? parsed) (pos? parsed))
      (invalid! (str (name field) " must be a positive integer.") field))
    parsed))

(defn- compute-size [value]
  (let [raw  (cond
               (keyword? value) (name value)
               :else (or (non-blank value) "AUTO"))
        size (-> raw str/upper-case keyword)]
    (when-not (compute-sizes size)
      (invalid! "Compute size must be one of AUTO, XS, S, M, L, or XL." :compute-size))
    size))

(defn normalize-details
  "Validate and normalize Metabase connection details without retaining any
  secret in exception data."
  [details]
  (let [catalog     (non-blank (:catalog details))
        schema      (non-blank (:schema details))
        username    (non-blank (:username details))
        password    (non-blank (:password details))
        basic-token (non-blank (:basic-token details))]
    (when-not catalog
      (invalid! "Catalog is required." :catalog))
    (when (not= (boolean username) (boolean password))
      (invalid! "Both username and password must be provided together." :username))
    (when-not (or basic-token (and username password))
      (invalid! "Provide username/password credentials or a Basic token." :authentication))
    {:base-url                (valid-base-url (:base-url details))
     :catalog                 catalog
     :schema                  schema
     :username                username
     :password                password
     :basic-token             basic-token
     :compute-size            (compute-size (:compute-size details))
     :connect-timeout-seconds (positive-integer (:connect-timeout-seconds details)
                                                default-connect-timeout-seconds
                                                :connect-timeout-seconds)
     :request-timeout-seconds (positive-integer (:request-timeout-seconds details)
                                                default-request-timeout-seconds
                                                :request-timeout-seconds)}))

(defn details->client
  ^LakehouseClient [details]
  (let [{:keys [base-url username password basic-token
                connect-timeout-seconds request-timeout-seconds]}
        (normalize-details details)
        config (doto (LakehouseClient$Config.)
                 (.baseUrl base-url)
                 (.connectTimeout (Duration/ofSeconds connect-timeout-seconds))
                 (.requestTimeout (Duration/ofSeconds request-timeout-seconds))
                 (.userAgentSuffix "metabase-altertable-driver/0.1.0"))]
    (if basic-token
      (.basicToken config basic-token)
      (.credentials config username password))
    (LakehouseClient. config)))

(defn compute-size-enum
  ^LakehouseClient$ComputeSize [details]
  (LakehouseClient$ComputeSize/valueOf (name (:compute-size (normalize-details details)))))

(defn query-request
  ^LakehouseClient$QueryRequest [details {:keys [query session-id max-rows offset timezone
                                                ephemeral visible requested-by query-id cache]
                                         :or {visible false, requested-by "metabase"}}]
  (let [{:keys [catalog schema] :as normalized} (normalize-details details)]
    (when-not (non-blank query)
      (invalid! "Query text is required." :query))
    (LakehouseClient$QueryRequest.
     query
     catalog
     schema
     session-id
     (compute-size-enum normalized)
     true
     (some-> max-rows long)
     (some-> offset long)
     timezone
     ephemeral
     visible
     requested-by
     query-id
     cache
     "duckdb")))

(defn- sdk-exception [^LakehouseClient$LakehouseException error]
  (let [status-code (.statusCode error)
        request-id  (.requestId error)
        message     (str "Altertable " (.operation error) " failed"
                         (when status-code (str " (HTTP " status-code ")"))
                         (when request-id (str " [request " request-id "]"))
                         ".")]
    (ex-info message
             {:type        :altertable/api-error
              :operation   (.operation error)
              :method      (.method error)
              :path        (.path error)
              :status-code status-code
              :retriable?  (.retriable error)
              :request-id  request-id}
             error)))

(defn- converting-iterator
  ^java.util.Iterator [^java.util.Iterator iterator]
  (reify java.util.Iterator
    (hasNext [_] (.hasNext iterator))
    (next [_]
      (mapv (fn [^JsonNode node] (results/json-node->value node))
            (.next iterator)))))

(defn- take-prefix! [^java.util.Iterator iterator size]
  (loop [rows []]
    (if (and (< (count rows) size) (.hasNext iterator))
      (recur (conj rows (.next iterator)))
      rows)))

(defn cancel-query!
  [^LakehouseClient lakehouse-client ^JsonNode metadata]
  (let [query-id   (some-> metadata (.get "query_id") .asText)
        session-id (some-> metadata (.get "session_id") .asText)]
    (when (and (non-blank query-id) (non-blank session-id))
      (.cancelQuery lakehouse-client (java.util.UUID/fromString query-id) session-id))))

(defn execute-query!
  "Execute a native query and pass Metabase column metadata plus a single-use
  row reducible to `respond`."
  [details native-query cancel-chan respond]
  (let [lakehouse-client (details->client details)
        done-chan        (async/chan 1)]
    (try
      (let [^LakehouseClient$QueryResult query-result
            (.query lakehouse-client (query-request details native-query))
            metadata  (.metadata query-result)
            iterator  (converting-iterator (.iterator query-result))
            prefix    (take-prefix! iterator 32)
            columns   (vec (.columns query-result))
            row-source (results/rows-reducible prefix iterator query-result)]
        (when cancel-chan
          (async/thread
            (let [[signal port] (async/alts!! [cancel-chan done-chan])]
              (when (and (= port cancel-chan) signal)
                (try
                  (cancel-query! lakehouse-client metadata)
                  (catch Exception _))))))
        (respond (results/infer-column-metadata columns prefix) row-source))
      (catch LakehouseClient$LakehouseException error
        (throw (sdk-exception error)))
      (finally
        (async/close! done-chan)))))
