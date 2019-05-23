(ns blaze.system
  "Application System

  Call `init!` to initialize the system and `shutdown!` to bring it down.
  The specs at the beginning of the namespace describe the config which has to
  be given to `init!``. The server port has a default of `8080`."
  (:require
    [clojure.core.cache :as cache]
    [clojure.spec.alpha :as s]
    [datomic.api :as d]
    [datomic-tools.schema :as dts]
    [integrant.core :as ig]
    [blaze.datomic.schema :as schema]
    [blaze.handler.app :as app-handler]
    [blaze.handler.cql-evaluation :as cql-evaluation-handler]
    [blaze.handler.fhir.capabilities :as fhir-capabilities-handler]
    [blaze.handler.fhir.create :as fhir-create-handler]
    [blaze.handler.fhir.delete :as fhir-delete-handler]
    [blaze.handler.fhir.read :as fhir-read-handler]
    [blaze.handler.fhir.search :as fhir-search-handler]
    [blaze.handler.fhir.transaction :as fhir-transaction-handler]
    [blaze.handler.fhir.update :as fhir-update-handler]
    [blaze.handler.health :as health-handler]
    [blaze.server :as server]
    [blaze.structure-definition :refer [read-structure-definitions
                                        read-other]]
    [taoensso.timbre :as log]))



;; ---- Specs -------------------------------------------------------------

(s/def :database/uri string?)
(s/def :config/database-conn (s/keys :req [:database/uri]))
(s/def :cache/threshold pos-int?)
(s/def :structure-definitions/path string?)
(s/def :config/base-uri string?)
(s/def :config/cache (s/keys :opt [:cache/threshold]))
(s/def :config/structure-definitions (s/keys :req [:structure-definitions/path]))
(s/def :config/fhir-capabilities-handler (s/keys :opt-un [:config/base-uri]))
(s/def :config/fhir-create-handler (s/keys :opt-un [:config/base-uri]))
(s/def :config/fhir-search-handler (s/keys :opt-un [:config/base-uri]))
(s/def :config/fhir-update-handler (s/keys :opt-un [:config/base-uri]))
(s/def :config/server (s/keys :opt-un [::server/port]))

(s/def :system/config
  (s/keys
    :req-un
    [:config/database-conn
     :config/cache
     :config/structure-definitions
     :config/fhir-capabilities-handler
     :config/fhir-create-handler
     :config/fhir-search-handler
     :config/fhir-update-handler
     :config/server]))



;; ---- Functions -------------------------------------------------------------

(def ^:private version "0.4")

(def ^:private base-uri "http://localhost:8080")

(def ^:private default-config
  {:structure-definitions {}

   :database-conn
   {:structure-definitions (ig/ref :structure-definitions)}

   :cache {}

   :health-handler {}

   :cql-evaluation-handler
   {:database/conn (ig/ref :database-conn)
    :cache (ig/ref :cache)}

   :fhir-capabilities-handler
   {:base-uri base-uri
    :version version
    :structure-definitions (ig/ref :structure-definitions)}

   :fhir-create-handler
   {:base-uri base-uri
    :database/conn (ig/ref :database-conn)}

   :fhir-delete-handler
   {:database/conn (ig/ref :database-conn)}

   :fhir-read-handler
   {:database/conn (ig/ref :database-conn)}

   :fhir-search-handler
   {:base-uri base-uri
    :database/conn (ig/ref :database-conn)}

   :fhir-transaction-handler
   {:database/conn (ig/ref :database-conn)}

   :fhir-update-handler
   {:base-uri base-uri
    :database/conn (ig/ref :database-conn)}

   :app-handler
   {:handlers
    {:handler/cql-evaluation (ig/ref :cql-evaluation-handler)
     :handler/health (ig/ref :health-handler)
     :handler.fhir/capabilities (ig/ref :fhir-capabilities-handler)
     :handler.fhir/create (ig/ref :fhir-create-handler)
     :handler.fhir/delete (ig/ref :fhir-delete-handler)
     :handler.fhir/read (ig/ref :fhir-read-handler)
     :handler.fhir/search (ig/ref :fhir-search-handler)
     :handler.fhir/transaction (ig/ref :fhir-transaction-handler)
     :handler.fhir/update (ig/ref :fhir-update-handler)}
    :version version}

   :server {:port 8080 :handler (ig/ref :app-handler)}})


(s/fdef init!
  :args (s/cat :config :system/config :keys (s/? (s/coll-of keyword?))))

(defn init!
  ([config]
   (ig/init (merge-with merge default-config config)))
  ([config keys]
   (ig/init (merge-with merge default-config config) keys)))


(defn shutdown! [system]
  (ig/halt! system))



;; ---- Integrant Hooks -------------------------------------------------------

(defmethod ig/init-key :structure-definitions
  [_ {:structure-definitions/keys [path]}]
  (let [structure-definitions (read-structure-definitions path)]
    (log/info "Read structure definitions from:" path "resulting in:"
              (count structure-definitions) "structure definitions")
    structure-definitions))


(defn- upsert-schema [uri structure-definitions]
  (let [conn (d/connect uri)
        _ @(d/transact-async conn (dts/schema))
        {:keys [tx-data]} @(d/transact-async conn (schema/structure-definition-schemas structure-definitions))]
    (log/info "Upsert schema in database:" uri "creating" (count tx-data) "new facts")))


(defmethod ig/init-key :database-conn
  [_ {:database/keys [uri] :keys [structure-definitions]}]
  (if (d/create-database uri)
    (log/info "Created database at:" uri)
    (log/info "Use existing database at:" uri))

  (log/info "Connect with database:" uri)

  (upsert-schema uri structure-definitions)

  (d/connect uri))


(defmethod ig/init-key :cache
  [_ {:cache/keys [threshold] :or {threshold 128}}]
  (atom (cache/lru-cache-factory {} :threshold threshold)))


(defmethod ig/init-key :health-handler
  [_ _]
  (health-handler/handler))


(defmethod ig/init-key :cql-evaluation-handler
  [_ {:database/keys [conn] :keys [cache]}]
  (cql-evaluation-handler/handler conn cache))


(defmethod ig/init-key :fhir-capabilities-handler
  [_ {:keys [base-uri version structure-definitions]}]
  (fhir-capabilities-handler/handler base-uri version structure-definitions))


(defmethod ig/init-key :fhir-create-handler
  [_ {:keys [base-uri] :database/keys [conn]}]
  (fhir-create-handler/handler base-uri conn))


(defmethod ig/init-key :fhir-delete-handler
  [_ {:database/keys [conn]}]
  (fhir-delete-handler/handler conn))


(defmethod ig/init-key :fhir-read-handler
  [_ {:database/keys [conn]}]
  (fhir-read-handler/handler conn))


(defmethod ig/init-key :fhir-search-handler
  [_ {:keys [base-uri] :database/keys [conn]}]
  (fhir-search-handler/handler base-uri conn))


(defmethod ig/init-key :fhir-transaction-handler
  [_ {:database/keys [conn]}]
  (fhir-transaction-handler/handler conn))


(defmethod ig/init-key :fhir-update-handler
  [_ {:keys [base-uri] :database/keys [conn]}]
  (fhir-update-handler/handler base-uri conn))


(defmethod ig/init-key :app-handler
  [_ {:keys [handlers version]}]
  (app-handler/handler handlers version))


(defmethod ig/init-key :server
  [_ {:keys [port handler]}]
  (server/init! port handler))


(defmethod ig/init-key :default
  [_ val]
  val)


(defmethod ig/halt-key! :server
  [_ server]
  (server/shutdown! server))