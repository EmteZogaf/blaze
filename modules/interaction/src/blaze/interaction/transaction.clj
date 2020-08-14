(ns blaze.interaction.transaction
  "FHIR batch/transaction interaction.

  https://www.hl7.org/fhir/http.html#transaction"
  (:require
    [blaze.anomaly :refer [ex-anom]]
    [blaze.async-comp :as ac]
    [blaze.bundle :as bundle]
    [blaze.db.api :as d]
    [blaze.executors :as ex]
    [blaze.fhir.spec :as fhir-spec]
    [blaze.handler.fhir.util :as fhir-util]
    [blaze.handler.util :as handler-util]
    [blaze.middleware.fhir.metrics :refer [wrap-observe-request-duration]]
    [blaze.uuid :refer [random-uuid]]
    [clojure.alpha.spec :as s2]
    [clojure.string :as str]
    [cognitect.anomalies :as anom]
    [integrant.core :as ig]
    [reitit.core :as reitit]
    [reitit.ring]
    [ring.util.response :as ring]
    [taoensso.timbre :as log])
  (:import
    [java.time.format DateTimeFormatter]))


(set! *warn-on-reflection* true)


(defn- prefix-expression [prefix issue]
  (update issue :fhir.issues/expression #(str prefix "." %)))


(defn- validate-entry
  {:arglists '([db idx entry])}
  [idx {:keys [resource] {:keys [method url] :as request} :request :as entry}]
  (let [[type id] (some-> url bundle/match-url)]
    (cond
      (nil? request)
      {::anom/category ::anom/incorrect
       ::anom/message "Missing request."
       :fhir/issue "value"
       :fhir.issue/expression (format "Bundle.entry[%d]" idx)}

      (nil? url)
      {::anom/category ::anom/incorrect
       ::anom/message "Missing url."
       :fhir/issue "value"
       :fhir.issue/expression (format "Bundle.entry[%d].request" idx)}

      (nil? method)
      {::anom/category ::anom/incorrect
       ::anom/message "Missing method."
       :fhir/issue "value"
       :fhir.issue/expression (format "Bundle.entry[%d].request" idx)}

      (not (#{"GET" "HEAD" "POST" "PUT" "DELETE" "PATCH"} method))
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown method `" method "`.")
       :fhir/issue "value"
       :fhir.issue/expression (format "Bundle.entry[%d].request.method" idx)}

      (not (#{"GET" "POST" "PUT" "DELETE"} method))
      {::anom/category ::anom/unsupported
       ::anom/message (str "Unsupported method `" method "`.")
       :fhir/issue "not-supported"
       :fhir.issue/expression (format "Bundle.entry[%d].request.method" idx)}

      (nil? type)
      {::anom/category ::anom/incorrect
       ::anom/message
       (format "Can't parse type from `entry.request.url` `%s`." url)
       :fhir/issue "value"
       :fhir.issue/expression (format "Bundle.entry[%d].request.url" idx)}

      (not (fhir-spec/type-exists? type))
      {::anom/category ::anom/incorrect
       ::anom/message
       (format "Unknown type `%s` in bundle entry URL `%s`." type url)
       :fhir/issue "value"
       :fhir.issue/expression (format "Bundle.entry[%d].request.url" idx)}

      (and (#{"POST" "PUT"} method) (not (map? resource)))
      {::anom/category ::anom/incorrect
       ::anom/message
       (format "Expected resource of entry %d to be a JSON Object." idx)
       :fhir/issue "structure"
       :fhir.issue/expression (format "Bundle.entry[%d].resource" idx)
       :fhir/operation-outcome "MSG_JSON_OBJECT"}

      (and (#{"POST" "PUT"} method) (not= type (:resourceType resource)))
      {::anom/category ::anom/incorrect
       :fhir/issue "invariant"
       :fhir.issue/expression
       [(format "Bundle.entry[%d].request.url" idx)
        (format "Bundle.entry[%d].resource.resourceType" idx)]
       :fhir/operation-outcome "MSG_RESOURCE_TYPE_MISMATCH"}

      (and (= "PUT" method) (nil? id))
      {::anom/category ::anom/incorrect
       ::anom/message "Can't parse id from `entry.request.url` `" url "`."
       :fhir/issue "value"}

      (and (= "PUT" method) (not (contains? resource :id)))
      {::anom/category ::anom/incorrect
       :fhir/issue "required"
       :fhir.issue/expression
       [(format "Bundle.entry[%d].resource.id" idx)]
       :fhir/operation-outcome "MSG_RESOURCE_ID_MISSING"}

      (and (= "PUT" method) (not (s2/valid? :fhir/id (:id resource))))
      {::anom/category ::anom/incorrect
       :fhir/issue "value"
       :fhir.issue/expression
       [(format "Bundle.entry[%d].resource.id" idx)]
       :fhir/operation-outcome "MSG_ID_INVALID"}

      (and (= "PUT" method) (not= id (:id resource)))
      {::anom/category ::anom/incorrect
       :fhir/issue "invariant"
       :fhir.issue/expression
       [(format "Bundle.entry[%d].request.url" idx)
        (format "Bundle.entry[%d].resource.id" idx)]
       :fhir/operation-outcome "MSG_RESOURCE_ID_MISMATCH"}

      (not (fhir-spec/valid? resource))
      (let [{:fhir/keys [issues]} (fhir-spec/explain-data resource)
            prefix (format "Bundle.entry[%d].resource" idx)]
        {::anom/category ::anom/incorrect
         ::anom/message "Resource invalid."
         :fhir/issues (mapv #(prefix-expression prefix %) issues)})


      :else
      (assoc entry :blaze/type type :blaze/id id))))


(defn- validate-entries
  [entries]
  (transduce
    (map-indexed vector)
    (completing
      (fn [res [idx entry]]
        (let [entry (validate-entry idx entry)]
          (if (::anom/category entry)
            (reduced entry)
            (conj res entry)))))
    []
    entries))


(defn- prepare-entry
  [{{:keys [method]} :request :keys [resource] :as entry}]
  (log/trace "prepare-entry" method (:resourceType resource) (:id resource))
  (cond
    (= "POST" method)
    (assoc-in entry [:resource :id] (str (random-uuid)))

    :else
    entry))


(defn- validate-and-prepare-bundle
  [{:keys [resourceType type] entries :entry}]
  (cond
    (not= "Bundle" resourceType)
    (throw
      (ex-anom
        {::anom/category ::anom/incorrect
         ::anom/message (str "Expected a Bundle but was `" resourceType "`.")
         :fhir/issue "value"}))

    (not (#{"batch" "transaction"} type))
    (throw
      (ex-anom
        {::anom/category ::anom/incorrect
         ::anom/message
         (str "Expected a Bundle type of batch or transaction but was `" type "`.")
         :fhir/issue "value"}))

    :else
    (if (= "transaction" type)
      (let [entries (validate-entries entries)]
        (if (::anom/category entries)
          (throw (ex-anom entries))
          (mapv prepare-entry entries)))
      entries)))


(defmulti build-response-entry
  "Builds the response entry."
  {:arglists '([context db request-entry])}
  (fn [_ _ {{:keys [method]} :request}] method))


(defn- last-modified [{:blaze.db.tx/keys [instant]}]
  (str instant))


(defmethod build-response-entry "POST"
  [{:keys [router return-preference]}
   db
   {{type :resourceType id :id} :resource}]
  (let [resource (d/resource db type id)
        {:blaze.db/keys [tx]} (meta resource)
        vid (str (:blaze.db/t tx))]
    (cond->
      {:response
       {:status "201"
        :etag (str "W/\"" vid "\"")
        :lastModified (last-modified tx)
        :location
        (fhir-util/versioned-instance-url router type id vid)}}

      (= "representation" return-preference)
      (assoc :resource resource))))


(defmethod build-response-entry "PUT"
  [{:keys [router return-preference]} db {{type :resourceType id :id} :resource}]
  (let [resource (d/resource db type id)
        {:blaze.db/keys [tx num-changes]} (meta resource)
        vid (str (:blaze.db/t tx))]
    (cond->
      {:response
       (cond->
         {:status (if (= 1 num-changes) "201" "200")
          :etag (str "W/\"" vid "\"")
          :lastModified (last-modified tx)}
         (= 1 num-changes)
         (assoc
           :location
           (fhir-util/versioned-instance-url router type id vid)))}

      (= "representation" return-preference)
      (assoc :resource resource))))


(defmethod build-response-entry "DELETE"
  [_ db _]
  {:response
   {:status "204"
    :lastModified (last-modified (d/tx db (d/basis-t db)))}})


(defn- strip-leading-slash [s]
  (if (str/starts-with? s "/")
    (subs s 1)
    s))


(defn- convert-http-date
  "Converts string `s` representing a HTTP date into a FHIR instant formatted
  string."
  [s]
  (->> (.parse DateTimeFormatter/RFC_1123_DATE_TIME s)
       (.format DateTimeFormatter/ISO_INSTANT)))


(defn- process-batch-entry
  [{:keys [handler] :blaze/keys [context-path]}
   {{:keys [method url]} :request :keys [resource]}]
  (let [url (strip-leading-slash (str/trim url))
        [url query-string] (str/split url #"\?")]
    (if (= "" url)
      (ac/completed-future
        (handler-util/bundle-error-response
          {::anom/category ::anom/incorrect
           ::anom/message (format "Invalid URL `%s` in bundle request." url)
           :fhir/issue "value"}))
      (let [request
            (cond->
              {:uri (str context-path "/" url)
               :request-method (keyword (str/lower-case method))}

              query-string
              (assoc :query-string query-string)

              resource
              (assoc :body resource))]
        (-> (handler request)
            (ac/then-apply
              (fn [{:keys [status body]
                    {etag "ETag"
                     last-modified "Last-Modified"
                     location "Location"}
                    :headers}]
                (cond->
                  {:response
                   (cond->
                     {:status (str status)}

                     etag
                     (assoc :etag etag)

                     last-modified
                     (assoc :lastModified (convert-http-date last-modified))

                     location
                     (assoc :location location))}

                  (and (#{200 201} status) body)
                  (assoc :resource body)

                  (<= 400 status)
                  (update :response assoc :outcome body))))
            (ac/exceptionally handler-util/bundle-error-response))))))


(defmulti process
  "Processes the prepared entries according the batch or transaction rules and
  returns the response entries."
  {:arglists '([context type request-entries])}
  (fn [_ type _] type))


(defmethod process "batch"
  [context _ request-entries]
  (let [futures (map #(process-batch-entry context %) request-entries)]
    (-> (ac/all-of futures)
        (ac/then-apply
          (fn [_]
            (mapv ac/join futures))))))


(defmethod process "transaction"
  [{:keys [node executor] :as context} _ request-entries]
  (-> (d/transact node (bundle/tx-ops request-entries))
      ;; it's important to switch to the transaction executor here, because
      ;; otherwise the central indexing thread would execute response building.
      (ac/then-apply-async
        (fn [db]
          (mapv #(build-response-entry context db %) request-entries))
        executor)))


(defn- handler-intern [node executor]
  (fn [{{:keys [type] :as bundle} :body :keys [headers]
        ::reitit/keys [router match]}]
    (-> (ac/supply-async #(validate-and-prepare-bundle bundle) executor)
        (ac/then-compose
          (let [context
                {:router router
                 :handler (reitit.ring/ring-handler router)
                 :blaze/context-path (-> match :data :blaze/context-path)
                 :node node
                 :executor executor
                 :return-preference (handler-util/preference headers "return")}]
            #(process context type %)))
        (ac/then-apply
          (fn [response-entries]
            (ring/response
              {:resourceType "Bundle"
               :type (str type "-response")
               :entry response-entries})))
        (ac/exceptionally handler-util/error-response))))


(defn- wrap-interaction-name [handler]
  (fn [{{:keys [type]} :body :as request}]
    (cond-> (handler request)
      (string? type)
      (ac/then-apply #(assoc % :fhir/interaction-name type)))))


(defn handler [node executor]
  (-> (handler-intern node executor)
      (wrap-interaction-name)
      (wrap-observe-request-duration)))


(defmethod ig/init-key :blaze.interaction.transaction/handler
  [_ {:keys [node executor]}]
  (log/info "Init FHIR transaction interaction handler")
  (handler node executor))


(defn- executor-init-msg []
  (format "Init FHIR transaction interaction executor with %d threads"
          (.availableProcessors (Runtime/getRuntime))))


(defmethod ig/init-key ::executor
  [_ _]
  (log/info (executor-init-msg))
  (ex/cpu-bound-pool "blaze-transaction-interaction-%d"))


(derive ::executor :blaze.metrics/thread-pool-executor)
