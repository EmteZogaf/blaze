(ns life-fhir-store.elm.type-infer
  (:require
    [camel-snake-kebab.core :refer [->kebab-case-string]]
    [clojure.spec.alpha :as s]
    [life-fhir-store.elm.spec]))


(defmulti infer-types*
  "Infers :life/source-type for expressions."
  {:arglists '([context expression])}
  (fn [_ {:keys [type]}]
    (keyword "elm" (->kebab-case-string type))))


(s/fdef infer-types
  :args (s/cat :context any? :expression :elm/expression))

(defn infer-types [context expression]
  (infer-types* context expression))


(s/fdef update-expression-def
  :args (s/cat :defs (s/coll-of :elm/expression-def)
               :name :elm/name
               :expression-def :elm/expression))

(defn- update-expression-def [defs name expression]
  (mapv
    (fn [def]
      (if (= name (:name def))
        (assoc def :expression expression)
        def))
    defs))


(s/fdef infer-expression-def-types
  :args (s/cat :context (s/keys :req-un [:elm/library])
               :expression-def :elm/expression-def))

(defn- infer-expression-def-types
  {:arglists '([context expression-def])}
  [context {:keys [name expression] eval-context :context}]
  (let [expression (infer-types (assoc context :eval-context eval-context) expression)]
    (update-in context [:library :statements :def]
               update-expression-def name expression)))


(defn infer-library-types
  [{{expression-defs :def} :statements :as library}]
  (:library (reduce infer-expression-def-types {:library library} expression-defs)))


(defmethod infer-types* :default
  [_ expression]
  expression)


(defn named-type-specifier [name]
  {:type "NamedTypeSpecifier" :name name})


(defn elm-type-specifier [elm-name]
  (named-type-specifier (str "{urn:hl7-org:elm-types:r1}" elm-name)))


(defn list-type-specifier [element-type]
  {:type "ListTypeSpecifier" :elementType element-type})


(defn named-list-type-specifier [name]
  (list-type-specifier (named-type-specifier name)))



;; 2. Structured Values

;; 2.3. Property
(defmethod infer-types* :elm/property
  [context {:keys [source scope] :as expression}]
  (let [{source-type-name :resultTypeName :as source}
        (some->> source (infer-types context))
        scope-type (get-in context [:life/scope-types scope])]
    (cond-> expression
      source
      (assoc :source source)
      source-type-name
      (assoc :life/source-type source-type-name)
      scope-type
      (assoc :life/source-type scope-type))))



;; 9. Reusing Logic

;; 9.2. ExpressionRef
(defn- find-by-name [name coll]
  (first (filter #(= name (:name %)) coll)))

(defmethod infer-types* :elm/expression-ref
  [{{{expression-defs :def} :statements} :library}
   {:keys [name] :as expression}]
  ;; TODO: look into other libraries (:libraryName)
  (let [{eval-context :context} (find-by-name name expression-defs)]
    (cond-> expression
      eval-context
      (assoc :life/eval-context eval-context))))


;; 9.4. FunctionRef
(defmethod infer-types* :elm/function-ref
  [context expression]
  (update expression :operand #(mapv (partial infer-types context) %)))



;; 10. Queries

;; 10.1. Query
(defn- infer-source-type
  "Infers the types on query sources and assocs them into the context under
  [:life/scope-types `alias`]."
  [context {:keys [alias expression]}]
  (let [{{scope-type :elementType} :resultTypeSpecifier} (infer-types context expression)]
    (cond-> context
      scope-type
      (assoc-in [:life/scope-types alias] (:name scope-type)))))


(defn- infer-relationship-types
  [context {equiv-operands :equivOperand such-that :suchThat :as relationship}]
  (let [context (infer-source-type context relationship)]
    (cond-> relationship
      (seq equiv-operands)
      (update :equivOperand #(mapv (partial infer-types context) %))
      (some? such-that)
      (update :suchThat #(infer-types context %)))))


(defn- infer-all-relationship-types
  [context expression]
  (update expression :relationship (partial mapv (partial infer-relationship-types context))))


(defn- infer-query-where-type
  [context {:keys [where] :as expression}]
  (cond-> expression
    where
    (assoc :where (infer-types context where))))


(defn- infer-query-return-type
  [context
   {{return :expression} :return
    [{first-source :expression}] :source
    :as expression}]
  (if return
    (let [return (infer-types context return)]
      (assoc-in expression [:return :expression] return))
    (let [first-source (infer-types context first-source)]
      (assoc-in expression [:source 0 :expression] first-source))))


(defmethod infer-types* :elm/query
  [context {sources :source :as expression}]
  (let [context (reduce infer-source-type context sources)]
    (->> expression
         (infer-all-relationship-types context)
         (infer-query-where-type context)
         (infer-query-return-type context))))


(defmethod infer-types* :elm/unary-expression
  [context expression]
  (update expression :operand #(infer-types context %)))


(defmethod infer-types* :elm/multiary-expression
  [context expression]
  (update expression :operand #(mapv (partial infer-types context) %)))



;; 12. Comparison Operators

(derive :elm/equal :elm/multiary-expression)
(derive :elm/equivalent :elm/multiary-expression)
(derive :elm/greater :elm/multiary-expression)
(derive :elm/greater-or-equal :elm/multiary-expression)
(derive :elm/less :elm/multiary-expression)
(derive :elm/less-or-equal :elm/multiary-expression)
(derive :elm/not-equal :elm/multiary-expression)



;; 13. Logical Operators

(derive :elm/and :elm/multiary-expression)
(derive :elm/implies :elm/multiary-expression)
(derive :elm/or :elm/multiary-expression)
(derive :elm/xor :elm/multiary-expression)
(derive :elm/not :elm/unary-expression)



;; 16. Arithmetic Operators

;; 16.1. Abs
(derive :elm/abs :elm/unary-expression)



;; 18. Date and Time Operators

;; 18.11. DurationBetween
(derive :elm/duration-between :elm/multiary-expression)



;; 20. List Operators

;; 20.25. SingletonFrom
(derive :elm/singleton-from :elm/unary-expression)



;; 21. Aggregate Operators

;; 21.4. Count
(defmethod infer-types* :elm/count
  [context expression]
  (update expression :source #(infer-types context %)))


;; 22. Type Operators

;; 22.1. As
(derive :elm/as :elm/unary-expression)


;; 22.19. ToDate
(derive :elm/to-date :elm/unary-expression)


;; 22.20. ToDateTime
(derive :elm/to-date-time :elm/unary-expression)


;; 22.24. ToQuantity
(derive :elm/to-quantity :elm/unary-expression)



;; 23. Clinical Operators

;; 23.4. CalculateAgeAt
(derive :elm/calculate-age-at :elm/multiary-expression)
