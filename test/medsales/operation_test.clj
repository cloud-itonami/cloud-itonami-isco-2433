(ns medsales.operation-test
  "Unit tests for the closed operation vocabulary.

  The property under test is that the vocabulary is an ALLOWLIST. A denylist
  over an open vocabulary passes every test that names a blocked op and admits
  everything nobody thought of — which is exactly what the pre-change governor
  did (see `medsales.operation`'s docstring for the measurements)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [medsales.operation :as op]))

(deftest supported-and-reserved-are-disjoint
  ;; If an op appeared in both, `supported?` and `reserved?` would disagree
  ;; about the same word and the governor's cond would silently pick one.
  (is (empty? (set/intersection (set (keys op/supported)) (set (keys op/reserved))))))

(deftest the-two-sets-that-constrain-this-actor-are-beyond-its-reach
  (testing "the actor cannot propose widening its own approved-indications set"
    (is (op/reserved? :register-product-indication))
    (is (false? (op/supported? :register-product-indication))))
  (testing "the actor cannot propose widening its own licensed-buyers set"
    (is (op/reserved? :grant-buyer-licence))
    (is (false? (op/supported? :grant-buyer-licence)))))

(deftest anything-undeclared-is-undeclared
  (testing "the vocabulary is closed: an op nobody named is not admitted by default"
    (doseq [o [:rush-the-order :ship-it :approve-everything :sell nil "approve-sale" 42]]
      (is (false? (op/supported? o)) (str "unexpectedly supported: " (pr-str o)))
      (is (false? (op/declared? o)) (str "unexpectedly declared: " (pr-str o))))))

(deftest declared-covers-both-halves
  (is (true? (op/declared? :approve-sale)))
  (is (true? (op/declared? :pay-kickback-to-prescriber)))
  (testing "undeclared and reserved are different answers, not one bucket"
    (is (true? (op/reserved? :pay-kickback-to-prescriber)))
    (is (false? (op/reserved? :rush-the-order)))))

(deftest every-reserved-op-explains-itself
  ;; A refusal that cannot say why is a refusal an operator cannot act on.
  (doseq [[o _] op/reserved]
    (let [r (op/reserved-reason o)]
      (is (string? r) (str "no reason for " o))
      (is (pos? (count r)) (str "empty reason for " o)))))

(deftest every-supported-op-declares-its-properties
  (doseq [[o m] op/supported]
    (is (contains? m :escalates?) (str o " does not declare :escalates?"))
    (is (contains? m :cites-product?) (str o " does not declare :cites-product?"))
    (is (string? (:summary m)) (str o " has no summary"))))

(deftest properties-belong-to-the-operation
  (is (true? (op/escalates? :approve-bulk-order)))
  (is (false? (op/escalates? :approve-sale)))
  (is (true? (op/cites-product? :approve-sale)))
  (is (true? (op/cites-product? :approve-bulk-order)))
  (testing "reserved and undeclared ops answer false; they are hard-blocked before this is consulted"
    (is (false? (op/escalates? :grant-buyer-licence)))
    (is (false? (op/cites-product? :grant-buyer-licence)))
    (is (false? (op/cites-product? :rush-the-order)))))

(deftest every-supported-op-cites-a-product
  ;; Stated as a test rather than a comment: `medsales.facts` has no mirror
  ;; guard for a non-citing op carrying a :product-id, because there is no such
  ;; op. Adding one to `supported` without writing that guard reopens the hole
  ;; this vocabulary closed, one op over -- and this test is what says so.
  (is (every? #(op/cites-product? %) (keys op/supported))
      "a non-citing op was added; medsales.facts needs a citation guard before this is safe"))

(deftest label-never-throws
  (testing "(name nil) throws; this is the reason label exists"
    (is (= "nil" (op/label nil)))
    (is (= "approve-sale" (op/label :approve-sale)))
    (is (= "approve-sale" (op/label "approve-sale")))
    (is (= "42" (op/label 42)))))

(deftest vocabulary-summary-names-everything-declared
  (doseq [o (concat (keys op/supported) (keys op/reserved))]
    (is (re-find (re-pattern (name o)) op/vocabulary-summary)
        (str o " missing from vocabulary-summary"))))
