(ns blaze.handler.app-test
  (:require
    [blaze.async.comp :as ac]
    [blaze.handler.app]
    [blaze.test-util :refer [given-thrown with-system]]
    [clojure.spec.alpha :as s]
    [clojure.spec.test.alpha :as st]
    [clojure.test :as test :refer [deftest testing]]
    [integrant.core :as ig]
    [juxt.iota :refer [given]]
    [ring.util.response :as ring]
    [taoensso.timbre :as log]))


(st/instrument)
(log/set-level! :trace)


(defn- fixture [f]
  (st/instrument)
  (f)
  (st/unstrument))


(test/use-fixtures :each fixture)


(deftest init-test
  (testing "nil config"
    (given-thrown (ig/init {:blaze.handler/app nil})
      :key := :blaze.handler/app
      :reason := ::ig/build-failed-spec
      [:explain ::s/problems 0 :pred] := `map?))

  (testing "missing config"
    (given-thrown (ig/init {:blaze.handler/app {}})
      :key := :blaze.handler/app
      :reason := ::ig/build-failed-spec
      [:explain ::s/problems 0 :pred] := `(fn ~'[%] (contains? ~'% :rest-api))
      [:explain ::s/problems 1 :pred] := `(fn ~'[%] (contains? ~'% :health-handler))))

  (testing "invalid rest-api"
    (given-thrown (ig/init {:blaze.handler/app {:rest-api ::invalid}})
      :key := :blaze.handler/app
      :reason := ::ig/build-failed-spec
      [:explain ::s/problems 0 :pred] := `(fn ~'[%] (contains? ~'% :health-handler))
      [:explain ::s/problems 1 :pred] := `fn?
      [:explain ::s/problems 1 :val] := ::invalid))

  (testing "invalid health"
    (given-thrown (ig/init {:blaze.handler/app {:health-handler ::invalid}})
      :key := :blaze.handler/app
      :reason := ::ig/build-failed-spec
      [:explain ::s/problems 0 :pred] := `(fn ~'[%] (contains? ~'% :rest-api))
      [:explain ::s/problems 1 :pred] := `fn?
      [:explain ::s/problems 1 :val] := ::invalid)))


(defn- rest-api [_]
  (ac/completed-future (ring/response ::rest-api)))


(defn- health-handler [_]
  (ac/completed-future (ring/response ::health-handler)))


(def system
  {:blaze.handler/app {:rest-api rest-api :health-handler health-handler}})


(deftest handler-test
  (testing "rest-api"
    (with-system [{handler :blaze.handler/app} system]
      @(handler
         {:uri "/" :request-method :get}
         (fn [response]
           (given response
             :status := 200
             :body := ::rest-api))
         identity)))

  (testing "health-handler"
    (with-system [{handler :blaze.handler/app} system]
      @(handler
         {:uri "/health" :request-method :get}
         (fn [response]
           (given response
             :status := 200
             :body := ::health-handler))
         identity))))