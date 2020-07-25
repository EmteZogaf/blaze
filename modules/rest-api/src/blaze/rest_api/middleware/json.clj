(ns blaze.rest-api.middleware.json
  (:require
    [blaze.anomaly :refer [ex-anom]]
    [blaze.async-comp :as ac]
    [blaze.handler.util :as handler-util]
    [cheshire.core :as json]
    [cheshire.parse :refer [*use-bigdecimals?*]]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [cognitect.anomalies :as anom]
    [prometheus.alpha :as prom]
    [ring.util.response :as ring]
    [taoensso.timbre :as log]))


(prom/defhistogram parse-duration-seconds
  "FHIR parsing latencies in seconds."
  {:namespace "fhir"}
  (take 17 (iterate #(* 2 %) 0.00001))
  "format")


(prom/defhistogram generate-duration-seconds
  "FHIR generating latencies in seconds."
  {:namespace "fhir"}
  (take 17 (iterate #(* 2 %) 0.00001))
  "format")


(defn- parse-json
  "Takes a request `body` and returns a deferred with the parsed JSON content
  with string keys and BigDecimal numbers.

  Executes the parsing on `parse-executor`. Returns an error deferred with an
  busy anomaly if parse-executor rejects the task.

  Returns an error deferred with an incorrect anomaly on parse errors."
  [body]
  (with-open [_ (prom/timer parse-duration-seconds "json")
              reader (io/reader body)]
    (binding [*use-bigdecimals?* true]
      (try
        (json/parse-stream reader keyword)
        (catch Exception e
          (throw (ex-anom #::anom{:category ::anom/incorrect
                                  :message (ex-message e)})))))))


(defn- handle-request
  [{:keys [request-method body] {:strs [content-type]} :headers :as request}
   executor]
  (if (and (#{:put :post} request-method)
           content-type
           (or (str/starts-with? content-type "application/fhir+json")
               (str/starts-with? content-type "application/json")))
    (ac/supply-async #(assoc request :body (parse-json body)) executor)
    (ac/completed-future request)))


(defn- generate-json [body]
  (try
    (with-open [_ (prom/timer generate-duration-seconds "json")]
      (json/generate-string body {:key-fn name}))
    (catch Exception e
      (log/error (log/stacktrace e))
      (json/generate-string
        {"resourceType" "OperationOutcome"
         "issue"
         [{"severity" "error"
           "code" "exception"
           "diagnostics" (ex-message e)}]}))))


(defn handle-response [{:keys [body] :as response}]
  (if (some? body)
    (-> (assoc response :body (generate-json body))
        (ring/content-type "application/fhir+json;charset=utf-8"))
    response))


(defn wrap-json
  "Parses the request body as JSON, calls `handler` and generates JSON from the
  response."
  [handler executor]
  (fn [request]
    (-> (handle-request request executor)
        (ac/then-compose handler)
        (ac/then-apply handle-response)
        (ac/exceptionally #(handle-response (handler-util/error-response %))))))
