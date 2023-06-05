(ns blaze.server
  "HTTP Server."
  (:require
    [blaze.server.spec]
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [ring.adapter.jetty9 :as ring-jetty]
    [ring.util.response :as ring]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn- server-request [request]
  (assoc request :blaze/request-arrived (System/nanoTime)))


(defn- wrap-server [handler server]
  (fn
    ([request]
     (-> request server-request handler (ring/header "Server" server)))
    ([request respond raise]
     (-> (server-request request)
         (handler #(respond (ring/header % "Server" server)) raise)))))


(defmethod ig/pre-init-spec :blaze/server [_]
  (s/keys :req-un [::port ::handler ::version]
          :opt-un [::name ::async? ::min-threads ::max-threads]))


(defmethod ig/init-key :blaze/server
  [_ {:keys [name port handler version async? min-threads max-threads]
      :or {name "main" async? false min-threads 8 max-threads 50}}]
  (log/info (format "Start %s server on port %d" name port))
  (ring-jetty/run-jetty
    (wrap-server handler (str "Blaze/" version))
    {:port port
     :async? async?
     :join? false
     :send-server-version? false
     :min-threads min-threads
     :max-threads max-threads}))


(defmethod ig/halt-key! :blaze/server
  [_ server]
  (log/info "Shutdown main server")
  (ring-jetty/stop-server server))
