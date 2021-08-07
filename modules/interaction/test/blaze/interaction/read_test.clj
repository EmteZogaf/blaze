(ns blaze.interaction.read-test
  "Specifications relevant for the FHIR read interaction:

  https://www.hl7.org/fhir/http.html#read
  https://www.hl7.org/fhir/operationoutcome.html
  https://www.hl7.org/fhir/http.html#ops"
  (:require
    [blaze.db.api-stub :refer [mem-node-system with-system-data]]
    [blaze.db.spec]
    [blaze.interaction.read]
    [blaze.middleware.fhir.db :refer [wrap-db]]
    [blaze.middleware.fhir.db-spec]
    [blaze.test-util :refer [with-system]]
    [clojure.spec.test.alpha :as st]
    [clojure.test :as test :refer [deftest is testing]]
    [juxt.iota :refer [given]]
    [reitit.core :as reitit]
    [taoensso.timbre :as log])
  (:import
    [java.time Instant]))


(st/instrument)
(log/set-level! :trace)


(defn- fixture [f]
  (st/instrument)
  (f)
  (st/unstrument))


(test/use-fixtures :each fixture)


(def system
  (assoc mem-node-system :blaze.interaction/read {}))


(def match
  {:data {:fhir.resource/type "Patient"}})


(defn wrap-defaults [handler]
  (fn [request]
    (handler (assoc request ::reitit/match match))))


(defmacro with-handler [[handler-binding] & body]
  `(with-system [{node# :blaze.db/node
                  handler# :blaze.interaction/read} system]
     (let [~handler-binding (-> handler# wrap-defaults (wrap-db node#))]
       ~@body)))


(defmacro with-handler-data [[handler-binding] txs & body]
  `(with-system-data [{node# :blaze.db/node
                       handler# :blaze.interaction/read} system]
     ~txs
     (let [~handler-binding (-> handler# wrap-defaults (wrap-db node#))]
       ~@body)))


(deftest handler-test
  (testing "returns Not-Found on non-existing resource"
    (with-handler [handler]
      (let [{:keys [status body]}
            @(handler {:path-params {:id "0"}})]

        (is (= 404 status))

        (given body
          :fhir/type := :fhir/OperationOutcome
          [:issue 0 :severity] := #fhir/code"error"
          [:issue 0 :code] := #fhir/code"not-found"
          [:issue 0 :diagnostics] := "Resource `/Patient/0` not found"))))

  (testing "returns Not-Found on invalid version id"
    (with-handler [handler]
      (let [{:keys [status body]}
            @(handler {:path-params {:id "0" :vid "a"}})]

        (is (= 404 status))

        (given body
          :fhir/type := :fhir/OperationOutcome
          [:issue 0 :severity] := #fhir/code"error"
          [:issue 0 :code] := #fhir/code"not-found"
          [:issue 0 :diagnostics] := "Resource `/Patient/0` with versionId `a` was not found."))))

  (testing "returns Gone on deleted resource"
    (with-handler-data [handler]
      [[[:put {:fhir/type :fhir/Patient :id "0"}]]
       [[:delete "Patient" "0"]]]

      (let [{:keys [status body headers]}
            @(handler {:path-params {:id "0"}})]

        (is (= 410 status))

        (testing "Transaction time in Last-Modified header"
          (is (= "Thu, 1 Jan 1970 00:00:00 GMT" (get headers "Last-Modified"))))

        (given body
          :fhir/type := :fhir/OperationOutcome
          [:issue 0 :severity] := #fhir/code"error"
          [:issue 0 :code] := #fhir/code"deleted"))))

  (testing "returns existing resource"
    (with-handler-data [handler]
      [[[:put {:fhir/type :fhir/Patient :id "0"}]]]

      (let [{:keys [status headers body]}
            @(handler {:path-params {:id "0"}})]

        (is (= 200 status))

        (testing "Transaction time in Last-Modified header"
          (is (= "Thu, 1 Jan 1970 00:00:00 GMT" (get headers "Last-Modified"))))

        (testing "Version in ETag header"
          ;; 1 is the T of the transaction of the resource update
          (is (= "W/\"1\"" (get headers "ETag"))))

        (given body
          :fhir/type := :fhir/Patient
          :id := "0"
          [:meta :versionId] := #fhir/id"1"
          [:meta :lastUpdated] := Instant/EPOCH))))

  (testing "returns existing resource on versioned read"
    (with-handler-data [handler]
      [[[:put {:fhir/type :fhir/Patient :id "0"}]]]

      (let [{:keys [status headers body]}
            @(handler {:path-params {:id "0" :vid "1"}})]

        (is (= 200 status))

        (testing "Transaction time in Last-Modified header"
          (is (= "Thu, 1 Jan 1970 00:00:00 GMT" (get headers "Last-Modified"))))

        (testing "Version in ETag header"
          ;; 1 is the T of the transaction of the resource update
          (is (= "W/\"1\"" (get headers "ETag"))))

        (given body
          :fhir/type := :fhir/Patient
          :id := "0"
          [:meta :versionId] := #fhir/id"1"
          [:meta :lastUpdated] := Instant/EPOCH)))))
