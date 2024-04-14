(ns blaze.handler.fhir.util
  "Utilities for FHIR interactions."
  (:refer-clojure :exclude [sync])
  (:require
   [blaze.anomaly :as ba]
   [blaze.fhir.spec]
   [blaze.fhir.spec.type.system :as system]
   [blaze.util :as u]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [reitit.core :as reitit])
  (:import
   [java.time ZoneId ZonedDateTime]
   [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

(defn parse-nat-long [s]
  (when-let [n (parse-long s)]
    (when-not (neg? n)
      n)))

(defn t
  "Returns the t (optional) of the database which should be stay stable.

  Tries to read the t from the query param `__t` and returns the first valid one
  if there is any."
  {:arglists '([query-params])}
  [{v "__t"}]
  (some parse-nat-long (u/to-seq v)))

(def ^:private ^:const default-page-size 50)
(def ^:private ^:const max-page-size 10000)

(defn page-size
  "Returns the page size taken from a possible `_count` query param.

  Returns the value from the first valid `_count` query param or `default`
  (defaults to 50). Limits value at `max` (defaults to 10000)."
  {:arglists
   '([query-params]
     [query-params max default])}
  ([query-params]
   (page-size query-params max-page-size default-page-size))
  ([{v "_count"} max default]
   (or (some #(some-> (parse-nat-long %) (min max)) (u/to-seq v)) default)))

(defn page-offset
  "Returns the page offset taken from a possible `__page-offset` query param.

  Returns the value from the first valid `__page-offset` query param or the
  default value of 0."
  {:arglists '([query-params])}
  [{v "__page-offset"}]
  (or (some parse-nat-long (u/to-seq v)) 0))

(defn page-type
  "Returns the value of the first valid `__page-type` query param or nil
  otherwise.

  Values have to be valid FHIR resource type names."
  {:arglists '([query-params])}
  [{v "__page-type"}]
  (some #(when (s/valid? :fhir.resource/type %) %) (u/to-seq v)))

(defn page-id
  "Returns the value of the first valid `__page-id` query param or nil
  otherwise.

  Values have to be valid FHIR id's."
  {:arglists '([query-params])}
  [{v "__page-id"}]
  (some #(when (s/valid? :blaze.resource/id %) %) (u/to-seq v)))

(defn elements
  "Returns a vector of keywords created from the comma separated values of 
   the first valid `_elements` query param or `[]` otherwise."
  {:arglists '([query-params])}
  [{v "_elements"}]
  (mapv keyword (some-> v (str/split #"\s*,\s*"))))

(defn- incorrect-date-msg [name value]
  (format "The value `%s` of the query param `%s` is no valid date." value name))

(defn date
  "Returns the value of the query param with `name` parsed as FHIR date or nil
  if not found.

  Returns an anomaly if the query param is available but can't be converted to a
  FHIR date."
  {:arglists '([query-params name])}
  [query-params name]
  (when-let [value (get query-params name)]
    (let [date (system/parse-date value)]
      (if (ba/anomaly? date)
        (ba/incorrect (incorrect-date-msg name value))
        date))))

(defn type-url
  "Returns the URL of a resource type like `[base]/[type]`."
  [{:blaze/keys [base-url] ::reitit/keys [router]} type]
  (let [{:keys [path]} (reitit/match-by-name router (keyword type "type"))]
    (str base-url path)))

(defn instance-url
  "Returns the URL of an instance (resource) like `[base]/[type]/[id]`."
  [context type id]
  ;; URLs are build by hand here, because id's do not need to be URL encoded
  ;; and the URL encoding in reitit is slow: https://github.com/metosin/reitit/issues/477
  (str (type-url context type) "/" id))

(defn versioned-instance-url
  "Returns the URL of a versioned instance (resource) like
  `[base]/[type]/[id]/_history/[vid]`."
  [context type id vid]
  ;; URLs are build by hand here, because id's do not need to be URL encoded
  ;; and the URL encoding in reitit is slow: https://github.com/metosin/reitit/issues/477
  (str (instance-url context type id) "/_history/" vid))

(def ^:private gmt (ZoneId/of "GMT"))

(defn last-modified
  "Returns the instant of `tx` formatted suitable for the Last-Modified HTTP
  header."
  {:arglists '([tx])}
  [{:blaze.db.tx/keys [instant]}]
  (->> (ZonedDateTime/ofInstant instant gmt)
       (.format DateTimeFormatter/RFC_1123_DATE_TIME)))

(defn etag
  "Returns the t of `tx` formatted as ETag."
  {:arglists '([tx])}
  [{:blaze.db/keys [t]}]
  (str "W/\"" t "\""))
