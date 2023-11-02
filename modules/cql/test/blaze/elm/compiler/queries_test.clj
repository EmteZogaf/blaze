(ns blaze.elm.compiler.queries-test
  "10. Queries

  Section numbers are according to
  https://cql.hl7.org/04-logicalspecification.html."
  (:require
    [blaze.db.api :as d]
    [blaze.db.api-stub :refer [mem-node-config with-system-data]]
    [blaze.elm.code :as code]
    [blaze.elm.code-spec]
    [blaze.elm.compiler :as c]
    [blaze.elm.compiler.core :as core]
    [blaze.elm.compiler.core-spec]
    [blaze.elm.compiler.test-util :as tu :refer [has-form]]
    [blaze.elm.literal]
    [blaze.elm.literal-spec]
    [blaze.elm.quantity :as quantity]
    [blaze.fhir.spec :as fhir-spec]
    [blaze.fhir.spec.type]
    [clojure.spec.test.alpha :as st]
    [clojure.test :as test :refer [are deftest is testing]]
    [juxt.iota :refer [given]]))


(st/instrument)
(tu/instrument-compile)


(defn- fixture [f]
  (st/instrument)
  (tu/instrument-compile)
  (f)
  (st/unstrument))


(test/use-fixtures :each fixture)


;; 10.1. Query
;;
;; The Query operator represents a clause-based query. The result of the query
;; is determined by the type of sources included as well as the clauses used
;; in the query.
(deftest compile-query-test
  (testing "Non-retrieve queries"
    (testing "Sort"
      (testing "ByDirection"
        (let [elm {:type "Query"
                   :source
                   [#elm/aliased-query-source [#elm/list [#elm/integer "2"
                                                          #elm/integer "1"
                                                          #elm/integer "1"]
                                               "S"]]
                   :sort {:by [{:type "ByDirection" :direction "asc"}]}}
              expr (c/compile {} elm)]

          (testing "eval"
            (is (= [1 2] (core/-eval expr {} nil nil))))

          (testing "form"
            (has-form expr '(sorted-vector-query distinct [2 1 1] asc)))))

      (testing "ByExpression"
        (let [elm {:type "Query"
                   :source
                   [#elm/aliased-query-source [#elm/list [#elm/quantity [2 "m"]
                                                          #elm/quantity [1 "m"]
                                                          #elm/quantity [1 "m"]]
                                               "S"]]
                   :sort
                   {:by
                    [{:type "ByExpression"
                      :direction "asc"
                      :expression
                      {:type "Property"
                       :path "value"
                       :scope "S"
                       :resultTypeName "{urn:hl7-org:elm-types:r1}decimal"}}]}}
              expr (c/compile {} elm)]

          (testing "eval"
            (is (= [(quantity/quantity 1 "m") (quantity/quantity 2 "m")] (core/-eval expr {} nil nil))))

          (testing "form"
            (has-form expr '(sorted-vector-query distinct
                                                 [(quantity 2 "m")
                                                  (quantity 1 "m")
                                                  (quantity 1 "m")]
                                                 [asc (:value S)]))))

        (testing "with IdentifierRef"
          (are [query res] (= res (core/-eval (c/compile {} query) {} nil nil))
            {:type "Query"
             :source [#elm/aliased-query-source [#elm/list [#elm/instance ["{urn:hl7-org:elm-types:r1}Code"
                                                                           {"system" #elm/string "foo"
                                                                            "code" #elm/string "c"}]
                                                            #elm/instance ["{urn:hl7-org:elm-types:r1}Code"
                                                                           {"system" #elm/string "bar"
                                                                            "code" #elm/string "c"}]]
                                                 "S"]]
             :sort
             {:by
              [{:type "ByExpression"
                :direction "asc"
                :expression
                {:type "IdentifierRef"
                 :name "system"}}]}}
            [(code/to-code "bar" nil "c")
             (code/to-code "foo" nil "c")]))))

    (testing "Return non-distinct"
      (let [elm {:type "Query"
                 :source [#elm/aliased-query-source [#elm/list [#elm/integer "1" #elm/integer "1"] "S"]]
                 :return {:distinct false :expression {:type "AliasRef" :name "S"}}}
            expr (c/compile {} elm)]

        (testing "eval"
          (is (= [1 1] (core/-eval expr {} nil nil))))

        (testing "form"
          (has-form expr '(vector-query (map (fn [S] (alias-ref S))) [1 1])))))

    (testing "with query hint optimize first"
      (let [elm {:type "Query"
                 :source [#elm/aliased-query-source [#elm/list [#elm/integer "1"
                                                                #elm/integer "1"]
                                                     "S"]]}
            expr (c/compile {:optimizations #{:first}} elm)]

        (testing "eval"
          (is (= [1] (into [] (core/-eval expr {} nil nil)))))

        (testing "form"
          (has-form expr '(eduction-query distinct [1 1]))))))

  (testing "Retrieve queries"
    (with-system-data [{:blaze.db/keys [node]} mem-node-config]
      [[[:put {:fhir/type :fhir/Patient :id "0"
               :gender #fhir/code"female"}]
        [:put {:fhir/type :fhir/Patient :id "1"
               :gender #fhir/code"male"}]]]

      (let [db (d/db node)
            retrieve #elm/retrieve{:type "Patient"}
            where #elm/equal[{:type "FunctionRef"
                              :name "ToString"
                              :operand [#elm/scope-property ["P" "gender"]]}
                             #elm/string "female"]
            return #elm/scope-property ["P" "gender"]]

        (testing "source only"
          (let [elm {:type "Query"
                     :source
                     [{:expression retrieve
                       :alias "P"}]}
                expr (c/compile {:node node :eval-context "Unfiltered"} elm)]

            (testing "eval"
              (given (core/-eval expr {:db db} nil nil)
                count := 2
                [0 fhir-spec/fhir-type] := :fhir/Patient
                [0 :id] := "0"
                [1 fhir-spec/fhir-type] := :fhir/Patient
                [1 :id] := "1"))

            (testing "form"
              (has-form expr '(vector-query distinct (retrieve "Patient"))))))

        (testing "with where clause"
          (let [elm {:type "Query"
                     :source
                     [{:expression retrieve
                       :alias "P"}]
                     :where where}
                expr (c/compile {:node node :eval-context "Unfiltered"} elm)]

            (testing "eval"
              (given (core/-eval expr {:db db} nil nil)
                count := 1
                [0 fhir-spec/fhir-type] := :fhir/Patient
                [0 :id] := "0"))

            (testing "form"
              (has-form expr
                '(vector-query
                   (comp
                     (filter
                       (fn [P]
                         (equal (call "ToString" (:gender P)) "female")))
                     distinct)
                   (retrieve "Patient"))))))

        (testing "with return clause"
          (let [elm {:type "Query"
                     :source
                     [{:expression retrieve
                       :alias "P"}]
                     :return {:expression return}}
                expr (c/compile {:node node :eval-context "Unfiltered"} elm)]

            (testing "eval"
              (given (core/-eval expr {:db db} nil nil)
                count := 2
                [0] := #fhir/code"female"
                [1] := #fhir/code"male"))

            (testing "form"
              (has-form expr
                '(vector-query
                   (comp
                     (map (fn [P] (:gender P)))
                     distinct)
                   (retrieve "Patient"))))))

        (testing "with where and return clauses"
          (let [elm {:type "Query"
                     :source
                     [{:expression retrieve
                       :alias "P"}]
                     :where where
                     :return {:expression return}}
                expr (c/compile {:node node :eval-context "Unfiltered"} elm)]

            (testing "eval"
              (given (core/-eval expr {:db db} nil nil)
                count := 1
                [0] := #fhir/code"female"))

            (testing "form"
              (has-form expr
                '(vector-query
                   (comp
                     (filter
                       (fn [P]
                         (equal (call "ToString" (:gender P)) "female")))
                     (comp
                       (map
                         (fn [P] (:gender P)))
                       distinct))
                   (retrieve "Patient")))))))))

  (testing "With clause"
    (with-system-data [{:blaze.db/keys [node]} mem-node-config]
      [[[:put {:fhir/type :fhir/Patient :id "0"}]
        [:put {:fhir/type :fhir/Encounter :id "0"
               :subject #fhir/Reference{:reference "Patient/0"}}]
        [:put {:fhir/type :fhir/Observation :id "0"
               :subject #fhir/Reference{:reference "Patient/0"}
               :encounter #fhir/Reference{:reference "Encounter/0"}}]
        [:put {:fhir/type :fhir/Observation :id "1"
               :subject #fhir/Reference{:reference "Patient/0"}}]]]

      (let [db (d/db node)
            patient (tu/resource db "Patient" "0")]

        (let [elm {:type "Query"
                   :source [#elm/aliased-query-source [#elm/retrieve{:type "Observation"} "O"]]
                   :relationship
                   [{:type "With"
                     :expression #elm/retrieve{:type "Encounter"}
                     :alias "E"
                     :suchThat #elm/equal [#elm/source-property [#elm/scope-property ["O" "encounter"] "reference"]
                                           #elm/concatenate [#elm/string "Encounter/" #elm/scope-property ["E" "id"]]]}]}
              expr (c/compile {:node node :eval-context "Patient"} elm)]

          (testing "eval"
            (given (core/-eval expr {:db db} patient nil)
              count := 1
              [0 fhir-spec/fhir-type] := :fhir/Observation
              [0 :id] := "0"))

          (testing "form"
            (has-form expr
              '(vector-query
                 (comp
                   (filter
                     (fn [O]
                       (exists
                         (fn [E]
                           (equal
                             (:reference (:encounter O))
                             (concatenate "Encounter/" (:id E))))
                         (retrieve "Encounter"))))
                   distinct)
                 (retrieve "Observation")))))

        (testing "including return clause"
          (let [elm {:type "Query"
                     :source [#elm/aliased-query-source [#elm/retrieve{:type "Observation"} "O"]]
                     :relationship
                     [{:type "With"
                       :expression #elm/retrieve{:type "Encounter"}
                       :alias "E"
                       :suchThat #elm/equal [#elm/source-property [#elm/scope-property ["O" "encounter"] "reference"]
                                             #elm/concatenate [#elm/string "Encounter/" #elm/scope-property ["E" "id"]]]}]
                     :return {:expression #elm/scope-property ["O" "id"]}}
                expr (c/compile {:node node :eval-context "Patient"} elm)]

            (testing "eval"
              (given (core/-eval expr {:db db} patient nil)
                count := 1
                [0] := "0"))

            (testing "form"
              (has-form expr
                '(vector-query
                   (comp
                     (filter
                       (fn [O]
                         (exists
                           (fn [E]
                             (equal
                               (:reference (:encounter O))
                               (concatenate "Encounter/" (:id E))))
                           (retrieve "Encounter"))))
                     (comp
                       (map
                         (fn [O] (:id O)))
                       distinct))
                   (retrieve "Observation"))))))

        (testing "including non-distinct return clause"
          (let [elm {:type "Query"
                     :source [#elm/aliased-query-source [#elm/retrieve{:type "Observation"} "O"]]
                     :relationship
                     [{:type "With"
                       :expression #elm/retrieve{:type "Encounter"}
                       :alias "E"
                       :suchThat #elm/equal [#elm/source-property [#elm/scope-property ["O" "encounter"] "reference"]
                                             #elm/concatenate [#elm/string "Encounter/" #elm/scope-property ["E" "id"]]]}]
                     :return {:distinct false :expression #elm/scope-property ["O" "id"]}}
                expr (c/compile {:node node :eval-context "Patient"} elm)]

            (testing "eval"
              (given (core/-eval expr {:db db} patient nil)
                count := 1
                [0] := "0"))

            (testing "form"
              (has-form expr
                '(vector-query
                   (comp
                     (filter
                       (fn [O]
                         (exists
                           (fn [E]
                             (equal
                               (:reference (:encounter O))
                               (concatenate "Encounter/" (:id E))))
                           (retrieve "Encounter"))))
                     (map
                       (fn [O] (:id O))))
                   (retrieve "Observation"))))))

        (testing "including where clause"
          (let [elm {:type "Query"
                     :source [#elm/aliased-query-source [#elm/retrieve{:type "Observation"} "O"]]
                     :relationship
                     [{:type "With"
                       :expression #elm/retrieve{:type "Encounter"}
                       :alias "E"
                       :suchThat #elm/equal [#elm/source-property [#elm/scope-property ["O" "encounter"] "reference"]
                                             #elm/concatenate [#elm/string "Encounter/" #elm/scope-property ["E" "id"]]]}]
                     :where #elm/equal [#elm/string "1" #elm/scope-property ["O" "id"]]}
                expr (c/compile {:node node :eval-context "Patient"} elm)]

            (testing "eval"
              (is (empty? (core/-eval expr {:db db} patient nil))))

            (testing "form"
              (has-form expr
                '(vector-query
                   (comp
                     (filter
                       (fn [O] (equal "1" (:id O))))
                     (filter
                       (fn [O]
                         (exists
                           (fn [E]
                             (equal
                               (:reference (:encounter O))
                               (concatenate "Encounter/" (:id E))))
                           (retrieve "Encounter"))))
                     distinct)
                   (retrieve "Observation")))))))))

  (testing "Without clause"
    (with-system-data [{:blaze.db/keys [node]} mem-node-config]
      [[[:put {:fhir/type :fhir/Patient :id "0"}]
        [:put {:fhir/type :fhir/Encounter :id "0"
               :subject #fhir/Reference{:reference "Patient/0"}}]
        [:put {:fhir/type :fhir/Observation :id "0"
               :subject #fhir/Reference{:reference "Patient/0"}
               :encounter #fhir/Reference{:reference "Encounter/0"}}]
        [:put {:fhir/type :fhir/Observation :id "1"
               :subject #fhir/Reference{:reference "Patient/0"}}]]]

      (let [elm {:type "Query"
                 :source [#elm/aliased-query-source [#elm/retrieve{:type "Observation"} "O"]]
                 :relationship
                 [{:type "Without"
                   :expression #elm/retrieve{:type "Encounter"}
                   :alias "E"
                   :suchThat #elm/equal [#elm/source-property [#elm/scope-property ["O" "encounter"] "reference"]
                                         #elm/concatenate [#elm/string "Encounter/" #elm/scope-property ["E" "id"]]]}]}
            expr (c/compile {:node node :eval-context "Patient"} elm)
            db (d/db node)
            patient (tu/resource db "Patient" "0")]

        (testing "eval"
          (given (core/-eval expr {:db db} patient nil)
            count := 1
            [0 fhir-spec/fhir-type] := :fhir/Observation
            [0 :id] := "1"))

        (testing "form"
          (has-form expr
            '(vector-query
               (comp
                 (filter
                   (fn [O]
                     (not-exists
                       (fn [E]
                         (equal
                           (:reference (:encounter O))
                           (concatenate "Encounter/" (:id E))))
                       (retrieve "Encounter"))))
                 distinct)
               (retrieve "Observation"))))))))


;; 10.3. AliasRef
;;
;; The AliasRef expression allows for the reference of a specific source within
;; the scope of a query.
(deftest compile-alias-ref-test
  (let [expr (c/compile {} {:type "AliasRef" :name "foo"})]
    (testing "eval"
      (is (= ::result (core/-eval expr {} nil {"foo" ::result}))))

    (testing "form"
      (has-form expr '(alias-ref foo)))))


;; 10.7. IdentifierRef
;;
;; The IdentifierRef type defines an expression that references an identifier
;; that is either unresolved, or has been resolved to an attribute in an
;; unambiguous iteration scope such as a sort. Implementations should attempt to
;; resolve the identifier, only throwing an error at compile-time (or run-time
;; for an interpretive system) if the identifier reference cannot be resolved.
(deftest compile-identifier-ref-test
  (let [expr (c/compile {} {:type "IdentifierRef" :name "foo"})]

    (testing "form"
      (has-form expr '(:foo default)))))


;; TODO 10.9. QueryLetRef
;;
;; The QueryLetRef expression allows for the reference of a specific let
;; definition within the scope of a query.
