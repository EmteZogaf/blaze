(ns life-fhir-store.elm.boolean
  "Implementation of the boolean type.

  Section numbers are according to
  https://cql.hl7.org/04-logicalspecification.html."
  (:require
    [life-fhir-store.elm.protocols :as p]))


;; 22.28. ToString
(extend-protocol p/ToString
  Boolean
  (to-string [x]
    (str x)))
