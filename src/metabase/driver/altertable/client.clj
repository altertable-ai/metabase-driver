(ns metabase.driver.altertable.client
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [metabase.driver.altertable.results :as results])
  (:import
   (ai.altertable.lakehouse LakehouseClient LakehouseClient$ComputeSize LakehouseClient$Config
                            LakehouseClient$LakehouseException LakehouseClient$QueryAllResult
                            LakehouseClient$QueryRequest LakehouseClient$QueryResult)
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

(defn- schema-filter-settings
  "Preserve Metabase schema-filters connection settings across normalization."
  [details]
  (let [filter-type     (non-blank (:schema-filters-type details))
        filter-patterns (non-blank (:schema-filters-patterns details))]
    (cond-> {}
      filter-type     (assoc :schema-filters-type filter-type)
      filter-patterns (assoc :schema-filters-patterns filter-patterns))))

(defn normalize-details
  "Validate and normalize Metabase connection details without retaining any
  secret in exception data."
  [details]
  (let [catalog  (non-blank (:catalog details))
        schema   (non-blank (:schema details))
        username (non-blank (:username details))
        password (non-blank (:password details))]
    (when-not catalog
      (invalid! "Catalog is required." :catalog))
    (when (not= (boolean username) (boolean password))
      (invalid! "Both username and password must be provided together." :username))
    (when-not (and username password)
      (invalid! "Username and password are required." :authentication))
    (merge
     {:base-url                (valid-base-url (:base-url details))
      :catalog                 catalog
      :schema                  schema
      :username                username
      :password                password
      :compute-size            (compute-size (:compute-size details))
      :connect-timeout-seconds (positive-integer (:connect-timeout-seconds details)
                                                 default-connect-timeout-seconds
                                                 :connect-timeout-seconds)
      :request-timeout-seconds (positive-integer (:request-timeout-seconds details)
                                                 default-request-timeout-seconds
                                                 :request-timeout-seconds)}
     (schema-filter-settings details))))

(defn sanitize-details
  "Best-effort normalization for persisted database details."
  [details]
  (when (some? details)
    (let [trimmed (into {}
                        (map (fn [[k v]]
                               [k (if (string? v) (str/trim v) v)])
                             details))]
      (try
        (normalize-details trimmed)
        (catch Exception _ trimmed)))))

(defn details->client
  ^LakehouseClient [details]
  (let [{:keys [base-url username password
                connect-timeout-seconds request-timeout-seconds]}
        (normalize-details details)
        config (doto (LakehouseClient$Config.)
                 (.baseUrl base-url)
                 (.connectTimeout (Duration/ofSeconds connect-timeout-seconds))
                 (.requestTimeout (Duration/ofSeconds request-timeout-seconds))
                 (.userAgentSuffix "metabase-altertable-driver/0.1.0"))]
    (.credentials config username password)
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
              :request-id  request-id
              :api-message (.getMessage error)}
             error)))

(defn api-error?
  "True when `ex` came from the Altertable SDK wrapper."
  [^Throwable ex]
  (= :altertable/api-error (:type (ex-data ex))))

(defn query-canceled?
  "True when `ex` represents a user-initiated query cancellation."
  [^Throwable ex]
  (or (:query-canceled? (ex-data ex))
      (when (api-error? ex)
        (let [{:keys [operation api-message status-code]} (ex-data ex)]
          (or (= "cancelQuery" operation)
              (= 499 status-code)
              (some-> api-message str/lower-case (str/includes? "cancel")))))
      (some-> (ex-message ex) str/lower-case (str/includes? "cancel"))))

(defn- exception-messages
  "Collect messages from `ex` and its causes."
  [^Throwable ex]
  (when ex
    (let [message (ex-message ex)]
      (if-let [cause (.getCause ex)]
        (cons message (exception-messages cause))
        (when message [message])))))

(defn table-known-to-not-exist?
  "True when `ex` indicates the referenced table does not exist."
  [^Throwable ex]
  (boolean
   (some (fn [message]
           (when message
             (re-find #"does not exist|unknown table|table with name .* does not exist"
                        (str/lower-case message))))
         (exception-messages ex))))

(defn humanize-connection-error
  "Map connection failures to Metabase connection error keywords."
  [messages]
  (let [message (str/join " " (remove str/blank? messages))]
    (cond
      (re-find #"(?i)http 401|unauthorized|invalid credentials" message)
      :username-or-password-incorrect

      (re-find #"(?i)http 403|forbidden" message)
      :username-or-password-incorrect

      (re-find #"(?i)http 404|catalog is required" message)
      :database-name-incorrect

      (re-find #"(?i)username and password" message)
      :password-required

      (re-find #"(?i)api url" message)
      :cannot-connect-check-host-and-port

      (re-find #"(?i)(connection refused|timed out|failed to connect|unknown host)"
               message)
      :cannot-connect-check-host-and-port

      :else (first messages))))

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

(defn- sql-string-literal [value]
  (str "'" (str/replace value "'" "''") "'"))

(defn- query-all-rows!
  [details native-query]
  (let [^LakehouseClient client (details->client details)]
    (try
      (let [^LakehouseClient$QueryAllResult result
            (.queryAll client (query-request details native-query))
            columns (vec (.columns result))]
        (mapv (fn [^java.util.List row]
                (mapv (fn [^JsonNode node]
                        (results/json-node->value node))
                      row))
              (.rows result)))
      (catch LakehouseClient$LakehouseException error
        (throw (sdk-exception error))))))

(def ^:private system-schemas
  #{"information_schema" "pg_catalog"})

(def ^:private syncable-table-types-sql
  "table_type IN ('BASE TABLE', 'VIEW')")

(defn- list-tables-sql
  [{:keys [catalog]}]
  (str "SELECT table_schema, table_name\n"
       "FROM information_schema.tables\n"
       "WHERE " syncable-table-types-sql "\n"
       "  AND table_catalog = " (sql-string-literal catalog) "\n"
       "ORDER BY table_schema, table_name"))

(defn- list-schemas-sql
  [{:keys [catalog]}]
  (str "SELECT schema_name\n"
       "FROM information_schema.schemata\n"
       "WHERE catalog_name = " (sql-string-literal catalog) "\n"
       "ORDER BY schema_name"))

(defn list-schemas!
  "Return syncable schema names in the configured catalog."
  [details]
  (let [normalized (normalize-details details)]
    (into []
          (comp (map first)
                (filter non-blank)
                (remove system-schemas))
          (query-all-rows! normalized {:query (list-schemas-sql normalized)}))))

(defn list-tables!
  "Return user tables in the configured catalog."
  [details]
  (let [normalized (normalize-details details)]
    (map (fn [[table-schema table-name]]
           {:schema table-schema
            :name   table-name})
         (query-all-rows! normalized {:query (list-tables-sql normalized)}))))

(defn- sql-in-list [values]
  (str/join ", " (map sql-string-literal values)))

(defn- lookup-table-schema
  "Resolve a table schema from information_schema when it is missing locally."
  [normalized table-name]
  (some first
        (query-all-rows! normalized
                         {:query (str "SELECT table_schema\n"
                                       "FROM information_schema.tables\n"
                                       "WHERE " syncable-table-types-sql "\n"
                                       "  AND table_catalog = "
                                       (sql-string-literal (:catalog normalized))
                                       "\n"
                                       "  AND table_name = "
                                       (sql-string-literal table-name)
                                       "\n"
                                       "ORDER BY table_schema\n"
                                       "LIMIT 1")})))

(defn- table-schema
  [details {:keys [schema name]}]
  (let [normalized (normalize-details details)]
    (or (non-blank schema)
        (non-blank (:schema normalized))
        (lookup-table-schema normalized name)
        (invalid! (str "Could not determine schema for table " name ".") :schema))))

(defn- describe-table-sql
  [{:keys [catalog]} table-schema table-name]
  (str "SELECT column_name, data_type, ordinal_position\n"
       "FROM information_schema.columns\n"
       "WHERE table_catalog = " (sql-string-literal catalog) "\n"
       "  AND table_schema = " (sql-string-literal table-schema) "\n"
       "  AND table_name = " (sql-string-literal table-name) "\n"
       "ORDER BY ordinal_position"))

(defn describe-table!
  "Return column metadata for a table in the configured catalog."
  [details {:keys [name schema] :as table}]
  (let [normalized    (normalize-details details)
        table-schema* (table-schema normalized table)]
    {:name   name
     :schema schema
     :fields (set
               (for [[column-name database-type ordinal-position]
                     (query-all-rows! normalized
                                      {:query (describe-table-sql normalized
                                                                  table-schema*
                                                                  name)})]
                 {:name              column-name
                  :database-type     database-type
                  :base-type         (results/database-type->base-type database-type)
                  :database-position (dec (long ordinal-position))}))}))

(defn- describe-fields-sql
  [{:keys [catalog]} {:keys [schema-names table-names]}]
  (str "SELECT table_schema, table_name, column_name, data_type, ordinal_position\n"
       "FROM information_schema.columns\n"
       "WHERE table_catalog = " (sql-string-literal catalog) "\n"
       (when (seq schema-names)
         (str "  AND table_schema IN (" (sql-in-list schema-names) ")\n"))
       (when (seq table-names)
         (str "  AND table_name IN (" (sql-in-list table-names) ")\n"))
       "ORDER BY table_schema, table_name, ordinal_position"))

(defn describe-fields!
  "Return column metadata for many tables in the configured catalog."
  [details & {:keys [schema-names table-names]}]
  (let [normalized (normalize-details details)]
    (map (fn [[table-schema table-name column-name database-type ordinal-position]]
           {:table-schema      table-schema
            :table-name        table-name
            :name              column-name
            :database-type     database-type
            :base-type         (results/database-type->base-type database-type)
            :database-position (dec (long ordinal-position))})
         (query-all-rows! normalized
                          {:query (describe-fields-sql normalized
                                                       {:schema-names schema-names
                                                        :table-names table-names})}))))

(defn- table-exists-sql
  [{:keys [catalog]} table-schema table-name]
  (str "SELECT 1\n"
       "FROM information_schema.tables\n"
       "WHERE " syncable-table-types-sql "\n"
       "  AND table_catalog = " (sql-string-literal catalog) "\n"
       "  AND table_schema = " (sql-string-literal table-schema) "\n"
       "  AND table_name = " (sql-string-literal table-name) "\n"
       "LIMIT 1"))

(defn table-exists?
  "Return true when `table` exists in the configured catalog."
  [details table]
  (let [normalized (normalize-details details)]
    (pos? (count (query-all-rows! normalized
                                  {:query (table-exists-sql normalized
                                                            (table-schema normalized table)
                                                            (:name table))})))))

(defn default-timezone
  "Return the database system timezone ID, if available."
  [details]
  (some-> (query-all-rows! (normalize-details details)
                           {:query "SELECT current_setting('TimeZone') AS tz"})
          first
          first
          str))

(defn- metadata-query-sql [query-sql]
  (str "SELECT * FROM (" query-sql ") AS _metabase_metadata LIMIT 1"))

(defn query-result-metadata
  "Infer result column metadata for a native SQL query."
  [details query-sql]
  (let [normalized (normalize-details details)
        ^LakehouseClient client (details->client normalized)]
    (try
      (let [^LakehouseClient$QueryAllResult result
            (.queryAll client (query-request normalized {:query (metadata-query-sql query-sql)}))
            columns (vec (.columns result))
            prefix  (mapv (fn [^java.util.List row]
                            (mapv (fn [^JsonNode node]
                                    (results/json-node->value node))
                                  row))
                          (.rows result))]
        (:cols (results/infer-column-metadata columns prefix)))
      (catch LakehouseClient$LakehouseException error
        (throw (sdk-exception error))))))

(defn test-connection!
  "Verify credentials and catalog access by running a lightweight query."
  [details]
  (let [^LakehouseClient client (details->client details)]
    (try
      (with-open [^LakehouseClient$QueryResult _result
                  (.query client (query-request details {:query "SELECT 1 AS ok"}))]
        true)
      (catch LakehouseClient$LakehouseException error
        (throw (sdk-exception error))))))

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
