(ns blaze.db.impl.search-param.chained-spec
  (:require
   [blaze.coll.spec :as cs]
   [blaze.db.impl.batch-db.spec]
   [blaze.db.impl.codec-spec]
   [blaze.db.impl.search-param.chained :as spc]
   [blaze.db.impl.search-param.spec]
   [blaze.db.search-param-registry.spec]
   [blaze.fhir.spec.spec]
   [clojure.spec.alpha :as s]
   [cognitect.anomalies :as anom]))

(s/fdef spc/targets
  :args (s/cat :batch-db :blaze.db.impl/batch-db
               :resource-handle :blaze.db/resource-handle
               :code :blaze.db/c-hash
               :target-tid (s/? :blaze.db/tid))
  :ret (cs/coll-of :blaze.db/resource-handle))

(s/fdef spc/parse-search-param
  :args (s/cat :registry :blaze.db/search-param-registry
               :type :fhir.resource/type
               :s string?)
  :ret (s/or :search-param :blaze.db/search-param :anomaly ::anom/anomaly))
