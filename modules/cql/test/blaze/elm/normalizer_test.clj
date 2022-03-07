(ns blaze.elm.normalizer-test
  "Section numbers are according to
  https://cql.hl7.org/04-logicalspecification.html."
  (:require
    [blaze.elm.literal :as elm]
    [blaze.elm.literal-spec]
    [blaze.elm.normalizer :refer [normalize]]
    [blaze.elm.normalizer-spec]
    [clojure.spec.test.alpha :as st]
    [clojure.test :as test :refer [deftest testing]]
    [juxt.iota :refer [given]]))


(st/instrument)


(defn- fixture [f]
  (st/instrument)
  (f)
  (st/unstrument))


(test/use-fixtures :each fixture)


(def expression-1
  {:type "Implies" :operand [#elm/string "A" #elm/string "B"]})


(def expression-2
  {:type "Xor" :operand [#elm/string "A" #elm/string "B"]})


(def expression-3
  {:type "In" :operand [#elm/string "A" #elm/string "B"]})


(def expression-4
  {:type "IncludedIn" :operand [#elm/string "A" #elm/string "B"]})




;; 2. Structured Values

;; 2.3. Property
(deftest normalize-property-test
  (testing "Normalizes the source of a Property expression"
    (given (normalize {:type "Property" :source expression-1})
      :source := (normalize expression-1))))



;; 10. Queries

;; 10.1. Query
(deftest normalize-query-test
  (testing "Normalizes the expression of a AliasedQuerySource in a Query expression"
    (given (normalize {:type "Query" :source [{:expression expression-1}]})
      :source := [{:expression (normalize expression-1)}]))

  (testing "Normalizes the expression of a LetClause in a Query expression"
    (given (normalize {:type "Query" :let [{:expression expression-1}]})
      :let := [{:expression (normalize expression-1)}]))

  (testing "Normalizes the expression of a RelationshipClause in a Query expression"
    (given (normalize {:type "Query" :relationship [{:expression expression-1}]})
      :relationship := [{:expression (normalize expression-1)}]))

  (testing "Normalizes the where expression of a Query expression"
    (given (normalize {:type "Query" :where expression-1})
      :where := (normalize expression-1)))

  (testing "Normalizes the expression of the ReturnClause in a Query expression"
    (given (normalize {:type "Query" :return {:expression expression-1}})
      :return := {:expression (normalize expression-1)}))

  ;; TODO: SortClause
  )



;; 12. Comparison Operators

;; 12.1. Equal
(deftest normalize-equal-test
  (testing "Normalizes both operands of an Equal expression"
    (given (normalize {:type "Equal" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))


;; 12.2. Equivalent
(deftest normalize-equivalent-test
  (testing "Normalizes both operands of an Equivalent expression"
    (given (normalize {:type "Equivalent" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))


;; 12.3. Greater
(deftest normalize-greater-test
  (testing "Normalizes both operands of a Greater expression"
    (given (normalize {:type "Greater" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))


;; 12.4. GreaterOrEqual
(deftest normalize-greater-or-equal-test
  (testing "Normalizes both operands of a GreaterOrEqual expression"
    (given (normalize {:type "GreaterOrEqual" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))


;; 12.5. Less
(deftest normalize-less-test
  (testing "Normalizes both operands of a Less expression"
    (given (normalize {:type "Less" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))


;; 12.6. LessOrEqual
(deftest normalize-less-or-equal-test
  (testing "Normalizes both operands of a LessOrEqual expression"
    (given (normalize {:type "LessOrEqual" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))


;; 12.7. NotEqual
(deftest normalize-not-equal-test
  (testing "A not-equal B normalizes to not (A equal B)"
    (given (normalize {:type "NotEqual" :operand [#elm/string "A" #elm/string "B"]})
      :type := "Not"
      [:operand :type] := "Equal"
      [:operand :operand] := [#elm/string "A" #elm/string "B"])))



;; 13. Logical Operators

;; 13.1. And
(deftest normalize-and-test
  (testing "Normalizes both operands of an And expression"
    (given (normalize {:type "And" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))


;; 13.2 Implies
(deftest normalize-implies-test
  (testing "A implies B normalizes to not A or B"
    (given (normalize {:type "Implies" :operand [#elm/string "A" #elm/string "B"]})
      :type := "Or"
      [:operand first :type] := "Not"
      [:operand first :operand] := #elm/string "A"
      [:operand second] := #elm/string "B")))


;; 13.3. Not
(deftest normalize-not-test
  (testing "Normalizes the operand of a Not expression"
    (given (normalize {:type "Not" :operand expression-1})
      :operand := (normalize expression-1))))


;; 13.4. Or
(deftest normalize-or-test
  (testing "Normalizes both operands of an Or expression"
    (given (normalize {:type "Or" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))


;; 13.5 Xor
(deftest normalize-xor-test
  (testing "Normalizes both operands of an Xor expression"
    (given (normalize {:type "Xor" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))



;; 14. Nullological Operators

;; 14.2. Coalesce
(deftest normalize-coalesce-test
  (testing "Normalizes all operands of an Coalesce expression"
    (given (normalize {:type "Coalesce" :operand [expression-1 expression-2]})
      :operand := [(normalize expression-1) (normalize expression-2)])))


;; 14.3. IsFalse
(deftest normalize-is-false-test
  (testing "Normalizes the operand of an IsFalse expression"
    (given (normalize {:type "IsFalse" :operand expression-1})
      :operand := (normalize expression-1))))


;; 14.4. IsNull
(deftest normalize-is-null-test
  (testing "Normalizes the operand of an IsNull expression"
    (given (normalize {:type "IsNull" :operand expression-1})
      :operand := (normalize expression-1))))


;; 14.5. IsTrue
(deftest normalize-is-true-test
  (testing "Normalizes the operand of an IsTrue expression"
    (given (normalize {:type "IsTrue" :operand expression-1})
      :operand := (normalize expression-1))))



;; 15. Conditional Operators

;; 15.1. Case
(deftest normalize-case-test
  (testing "Normalizes the case item and else"
    (given (normalize {:type "Case"
                       :caseItem
                       [{:when expression-1
                         :then expression-2}]
                       :else expression-3})
      [:caseItem 0 :when] := (normalize expression-1)
      [:caseItem 0 :then] := (normalize expression-2)
      :else := (normalize expression-3)))

  (testing "Normalizes the optional comparand"
    (given (normalize {:type "Case"
                       :comparand expression-1
                       :caseItem
                       [{:when expression-2
                         :then expression-3}]
                       :else expression-4})
      :comparand := (normalize expression-1)
      [:caseItem 0 :when] := (normalize expression-2)
      [:caseItem 0 :then] := (normalize expression-3)
      :else := (normalize expression-4))))


;; 15.2. If
(deftest normalize-if-test
  (testing "Normalizes all arguments"
    (given (normalize {:type "If"
                       :condition expression-1
                       :then expression-2
                       :else expression-3})
      :condition := (normalize expression-1)
      :then := (normalize expression-2)
      :else := (normalize expression-3))))



;; 20. List Operators

;; 20.4. Distinct
(deftest normalize-distinct-test
  (given (normalize (elm/distinct expression-1))
    :operand := (normalize expression-1)))


;; 20.8. Exists
(deftest normalize-exists-test
  (given (normalize (elm/exists expression-1))
    :operand := (normalize expression-1)))


;; 20.10. First
(deftest normalize-first-test
  (given (normalize (elm/first expression-1))
    :source := (normalize expression-1)))


;; 20.11. Flatten
(deftest normalize-flatten-test
  (given (normalize (elm/flatten expression-1))
    :operand := (normalize expression-1)))


;; 20.25. SingletonFrom
(deftest normalize-singleton-from-test
  (given (normalize (elm/singleton-from expression-1))
    :operand := (normalize expression-1)))
