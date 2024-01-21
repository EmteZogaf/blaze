(ns blaze.db.kv.spec
  (:require
   [blaze.db.kv :as kv]
   [clojure.spec.alpha :as s])
  (:import
   [java.lang AutoCloseable]))

(s/def :blaze.db/kv-store
  kv/store?)

(s/def :blaze.db/kv-snapshot
  (s/and #(satisfies? kv/KvSnapshot %)
         #(instance? AutoCloseable %)))

(s/def :blaze.db/kv-iterator
  (s/and #(satisfies? kv/KvIterator %)
         #(instance? AutoCloseable %)))

(s/def ::kv/put-entry
  (s/tuple keyword? bytes? bytes?))

(s/def ::kv/delete-entry
  (s/tuple keyword? bytes?))

(defmulti write-entry first)

(defmethod write-entry :put [_]
  (s/cat :op #{:put} :column-family keyword? :key bytes? :val bytes?))

(defmethod write-entry :merge [_]
  (s/cat :op #{:merge} :column-family keyword? :key bytes? :val bytes?))

(defmethod write-entry :delete [_]
  (s/cat :op #{:delete} :column-family keyword? :key bytes?))

(s/def ::kv/write-entry
  (s/multi-spec write-entry first))

(s/def ::kv/column-families
  (s/map-of keyword (s/nilable map?)))
