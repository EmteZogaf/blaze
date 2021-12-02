(ns blaze.elm.normalizer
  (:require
    [blaze.elm.spec]
    [cuerdas.core :as str]))


(defmulti normalize
  {:arglists '([expression])}
  (fn [{:keys [type]}]
    (assert type)
    (keyword "elm.normalizer.type" (str/kebab type))))


(defn- normalize-expression [x]
  (update x :expression normalize))


(defn- update-expression-defs [expression-defs]
  (mapv normalize-expression expression-defs))


(defn normalize-library [library]
  (update-in library [:statements :def] update-expression-defs))


(defmethod normalize :default
  [expression]
  expression)


(defn- un-pred [name operand]
  {:type name
   :operand operand
   :resultTypeName "{urn:hl7-org:elm-types:r1}Boolean"})


(defn- bin-pred [name operand-1 operand-2]
  {:type name
   :operand [operand-1 operand-2]
   :resultTypeName "{urn:hl7-org:elm-types:r1}Boolean"})


;; 2. Structured Values

;; 2.3. Property
(defmethod normalize :elm.normalizer.type/property
  [{:keys [source] :as expression}]
  (let [source (some-> source normalize)]
    (cond-> expression
      source
      (assoc :source source))))



;; 8. Expressions

;; 8.3. UnaryExpression
(defmethod normalize :elm.normalizer.type/unary-expression
  [{:keys [operand] :as expression}]
  (assoc expression :operand (normalize operand)))


;; 8.4. BinaryExpression
;; 8.5. TernaryExpression
;; 8.6. NaryExpression
(defmethod normalize :elm.normalizer.type/multiary-expression
  [{:keys [operand] :as expression}]
  (assoc expression :operand (mapv normalize operand)))



;; 10. Queries

;; 10.1. Query
(defmethod normalize :elm.normalizer.type/query
  [{:keys [source relationship where return] let' :let :as expression}]
  (cond-> (assoc expression :source (mapv normalize-expression source))
    let'
    (assoc :let (mapv normalize-expression let'))

    relationship
    (assoc :relationship (mapv normalize-expression relationship))

    where
    (assoc :where (normalize where))

    return
    (assoc :return (update return :expression normalize))))



;; 12. Comparison Operators

;; 12.1. Equal
(derive :elm.normalizer.type/equal :elm.normalizer.type/multiary-expression)


;; 12.2. Equivalent
(derive :elm.normalizer.type/equivalent :elm.normalizer.type/multiary-expression)


;; 12.3. Greater
(derive :elm.normalizer.type/greater :elm.normalizer.type/multiary-expression)


;; 12.4. GreaterOrEqual
(derive :elm.normalizer.type/greater-or-equal :elm.normalizer.type/multiary-expression)


;; 12.5. Less
(derive :elm.normalizer.type/less :elm.normalizer.type/multiary-expression)


;; 12.6. LessOrEqual
(derive :elm.normalizer.type/less-or-equal :elm.normalizer.type/multiary-expression)


;; 12.7. NotEqual
(defmethod normalize :elm.normalizer.type/not-equal
  [{[operand-1 operand-2] :operand}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (un-pred "Not" (bin-pred "Equal" operand-1 operand-2))))


;; 13. Logical Operators

;; 13.1 And
(derive :elm.normalizer.type/and :elm.normalizer.type/multiary-expression)


;; 13.2 Implies
(defmethod normalize :elm.normalizer.type/implies
  [{[operand-1 operand-2] :operand}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (bin-pred "Or" (un-pred "Not" operand-1) operand-2)))


;; 13.3. Not
(derive :elm.normalizer.type/not :elm.normalizer.type/unary-expression)


;; 13.4 Or
(derive :elm.normalizer.type/or :elm.normalizer.type/multiary-expression)


;; 13.5 Xor
(derive :elm.normalizer.type/xor :elm.normalizer.type/multiary-expression)



;; 14. Nullological Operators

;; 14.2. Coalesce
(derive :elm.normalizer.type/coalesce :elm.normalizer.type/multiary-expression)


;; 14.3. IsFalse
(derive :elm.normalizer.type/is-false :elm.normalizer.type/unary-expression)


;; 14.4. IsNull
(derive :elm.normalizer.type/is-null :elm.normalizer.type/unary-expression)


;; 14.3. IsTrue
(derive :elm.normalizer.type/is-true :elm.normalizer.type/unary-expression)



;; 15. Conditional Operators

;; 15.1. Case
(defn- normalize-case-item [item]
  (-> (update item :when normalize)
      (update :then normalize)))


(defmethod normalize :elm.normalizer.type/case
  [{:keys [comparand] :as expression}]
  (cond->
    (-> (update expression :caseItem (partial mapv normalize-case-item))
        (update :else normalize))
    comparand
    (assoc :comparand (normalize comparand))))


;; 15.2. If
(defmethod normalize :elm.normalizer.type/if
  [expression]
  (-> (update expression :condition normalize)
      (update :then normalize)
      (update :else normalize)))



;; 16. Arithmetic Operators

;; 16.1. Abs
(derive :elm.normalizer.type/abs :elm.normalizer.type/unary-expression)


;; 16.2. Add
(derive :elm.normalizer.type/add :elm.normalizer.type/multiary-expression)


;; 16.3. Ceiling
(derive :elm.normalizer.type/ceiling :elm.normalizer.type/unary-expression)


;; 16.4. Divide
(derive :elm.normalizer.type/divide :elm.normalizer.type/multiary-expression)


;; 16.5. Exp
(derive :elm.normalizer.type/exp :elm.normalizer.type/unary-expression)


;; 16.6. Floor
(derive :elm.normalizer.type/floor :elm.normalizer.type/unary-expression)


;; 16.7. HighBoundary
(derive :elm.normalizer.type/high-boundary :elm.normalizer.type/multiary-expression)


;; 16.8. Log
(derive :elm.normalizer.type/log :elm.normalizer.type/multiary-expression)


;; 16.9. LowBoundary
(derive :elm.normalizer.type/low-boundary :elm.normalizer.type/multiary-expression)


;; 16.10. Ln
(derive :elm.normalizer.type/ln :elm.normalizer.type/unary-expression)


;; 16.13. Modulo
(derive :elm.normalizer.type/modulo :elm.normalizer.type/multiary-expression)


;; 16.14. Multiply
(derive :elm.normalizer.type/multiply :elm.normalizer.type/multiary-expression)


;; 16.15. Negate
(derive :elm.normalizer.type/negate :elm.normalizer.type/unary-expression)


;; 16.16. Power
(derive :elm.normalizer.type/power :elm.normalizer.type/multiary-expression)


;; 16.17. Precision
(derive :elm.normalizer.type/precision :elm.normalizer.type/unary-expression)


;; 16.18. Predecessor
(derive :elm.normalizer.type/predecessor :elm.normalizer.type/unary-expression)


;; 16.19. Round
(defmethod normalize :elm.normalizer.type/round
  [{operand :operand :keys [precision] :as expression}]
  (cond-> (assoc expression :operand (normalize operand))
    precision
    (assoc :precision (normalize precision))))


;; 16.20. Subtract
(derive :elm.normalizer.type/subtract :elm.normalizer.type/multiary-expression)


;; 16.21. Successor
(derive :elm.normalizer.type/successor :elm.normalizer.type/unary-expression)


;; 16.22. Truncate
(derive :elm.normalizer.type/truncate :elm.normalizer.type/unary-expression)


;; 16.23. TruncatedDivide
(derive :elm.normalizer.type/truncated-divide :elm.normalizer.type/multiary-expression)



;; 19. Interval Operators

;; 19.12. In
(defmethod normalize :elm.normalizer.type/in
  [{[operand-1 operand-2] :operand :keys [precision]}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (cond-> (bin-pred "Contains" operand-2 operand-1)
      precision
      (assoc :precision precision))))


;; 19.14. IncludedIn
(defmethod normalize :elm.normalizer.type/included-in
  [{[operand-1 operand-2] :operand :keys [precision]}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (cond-> (bin-pred "Includes" operand-2 operand-1)
      precision
      (assoc :precision precision))))


;; 19.16. Meets
(defmethod normalize :elm.normalizer.type/meets
  [{[operand-1 operand-2] :operand :keys [precision]}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (bin-pred
      "Or"
      (cond-> (bin-pred "MeetsBefore" operand-1 operand-2)
        precision
        (assoc :precision precision))
      (cond-> (bin-pred "MeetsAfter" operand-1 operand-2)
        precision
        (assoc :precision precision)))))


;; 19.20. Overlaps
(defmethod normalize :elm.normalizer.type/overlaps
  [{[operand-1 operand-2] :operand :keys [precision]}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (bin-pred
      "Or"
      (normalize
        (cond-> (bin-pred "OverlapsBefore" operand-1 operand-2)
          precision
          (assoc :precision precision)))
      (normalize
        (cond-> (bin-pred "OverlapsAfter" operand-1 operand-2)
          precision
          (assoc :precision precision))))))


;; 19.21. OverlapsBefore
(defmethod normalize :elm.normalizer.type/overlaps-before
  [{[operand-1 operand-2] :operand :keys [precision]}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (cond->
      (bin-pred
        "ProperContains"
        operand-1
        (cond->
          {:type "Start"
           :operand operand-2}
          (:resultTypeName operand-2)
          (assoc :resultTypeName (:resultTypeName operand-2))))
      precision
      (assoc :precision precision))))


;; 19.22. OverlapsAfter
(defmethod normalize :elm.normalizer.type/overlaps-after
  [{[operand-1 operand-2] :operand :keys [precision]}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (cond->
      (bin-pred
        "ProperContains"
        operand-1
        (cond->
          {:type "End"
           :operand operand-2}
          (:resultTypeName operand-2)
          (assoc :resultTypeName (:resultTypeName operand-2))))
      precision
      (assoc :precision precision))))


;; 19.25. ProperIn
(defmethod normalize :elm.normalizer.type/proper-in
  [{[operand-1 operand-2] :operand :keys [precision]}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (cond-> (bin-pred "ProperContains" operand-2 operand-1)
      precision
      (assoc :precision precision))))


;; 19.27. ProperIncludedIn
(defmethod normalize :elm.normalizer.type/proper-included-in
  [{[operand-1 operand-2] :operand :keys [precision]}]
  (let [operand-1 (normalize operand-1)
        operand-2 (normalize operand-2)]
    (cond-> (bin-pred "ProperIncludes" operand-2 operand-1)
      precision
      (assoc :precision precision))))



;; 20. List Operators

;; 20.4. Distinct
(derive :elm.normalizer.type/distinct :elm.normalizer.type/unary-expression)

;; 20.8. Exists
(derive :elm.normalizer.type/exists :elm.normalizer.type/unary-expression)


;; 20.10. First
(defmethod normalize :elm.normalizer.type/first
  [expression]
  (update expression :source normalize))


;; 20.11. Flatten
(derive :elm.normalizer.type/flatten :elm.normalizer.type/unary-expression)


;; 20.25. SingletonFrom
(derive :elm.normalizer.type/singleton-from :elm.normalizer.type/unary-expression)



;; 23. Clinical Operators

;; 23.3. CalculateAge
(defmethod normalize :elm.normalizer.type/calculate-age
  [{birth-date :operand :keys [precision]}]
  (let [birth-date (normalize birth-date)]
    (cond->
      {:type "CalculateAgeAt"
       :operand [birth-date {:type "Today"}]
       :resultTypeName "{urn:hl7-org:elm-types:r1}Integer"}
      precision
      (assoc :precision precision))))
