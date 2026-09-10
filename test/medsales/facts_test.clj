(ns medsales.facts-test
  "Unit tests for the named predicates the Governor asks.

  Each of the four defects recorded in `medsales.facts`'s docstring has a test
  here that fails if the repair is reverted. They are unit tests rather than
  sim scenarios because the defects were in the SHAPE of a comparison, and a
  shape is what an inline expression does not let you name or test."
  (:require [clojure.test :refer [deftest is testing]]
            [medsales.facts :as facts]
            [medsales.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "C-1" :name "Awai Medical Supply"})
    (store/register-product! st {:product-id "P-1" :client-id "C-1"
                                 :approved-indications #{"coronary-stenosis"}
                                 :restricted? true})
    (store/register-product! st {:product-id "P-2" :client-id "C-1"
                                 :approved-indications #{"barrier-protection"}
                                 :restricted? false})
    (store/register-licensed-buyer! st "C-1" "B-LICENSED")
    st))

(defn- rules [vs] (into #{} (map :rule) vs))

;; ── defect 2 and 3: the confidence comparison ─────────────────────────────

(deftest usable-confidence-tests-number-first
  (testing "non-numeric confidences are answered, not thrown on and not admitted"
    ;; Pre-change `(< c 0.6)` threw ClassCastException on clj and returned
    ;; false on cljs. Both were wrong, in opposite directions.
    (doseq [c ["high" :high nil [] {}]]
      (is (false? (facts/usable-confidence? c)) (str "admitted: " (pr-str c)))
      (is (false? (facts/low-confidence? c)) (str "routed as merely low: " (pr-str c)))))
  (testing "the floor has a ceiling"
    (is (false? (facts/usable-confidence? 99.0)))
    (is (false? (facts/usable-confidence? 1.0000001)))
    (is (false? (facts/usable-confidence? -5))))
  (testing "the boundaries themselves are usable"
    (is (true? (facts/usable-confidence? 0)))
    (is (true? (facts/usable-confidence? 1)))
    (is (true? (facts/usable-confidence? 0.6)))))

(deftest the-confidence-floor-is-exclusive-at-the-boundary
  ;; A comparison with no case exactly ON the line cannot tell < from <=.
  (is (true? (facts/low-confidence? 0.5999)))
  (is (false? (facts/low-confidence? facts/confidence-floor))
      "confidence exactly at the floor is NOT low -- the floor is the lowest admissible value")
  (is (false? (facts/low-confidence? 0.6001))))

(deftest an-unusable-confidence-is-a-hard-violation-not-an-escalation
  (is (= #{:unusable-confidence} (rules (facts/confidence-violations {:confidence "high"}))))
  (is (= #{:unusable-confidence} (rules (facts/confidence-violations {:confidence 99.0}))))
  (is (= #{:unusable-confidence} (rules (facts/confidence-violations {}))))
  (is (empty? (facts/confidence-violations {:confidence 0.9}))))

;; ── defect 4: provenance ──────────────────────────────────────────────────

(deftest identified-rejects-the-ids-that-are-not-ids
  (is (true? (facts/identified? "C-1")))
  (doseq [id [nil "" "   " :C-1 42]]
    (is (false? (facts/identified? id)) (str "accepted as an id: " (pr-str id)))))

(deftest the-empty-map-client-cannot-answer-for-a-request-that-names-no-client
  ;; The measured pre-change defect: `{}` registered as a client lands under
  ;; the nil key, and a request with no :client-id finds it.
  (let [st (store/mem-store)]
    (store/register-client! st {})
    (is (= #{:no-client-id} (rules (facts/provenance-violations st {}))))
    (testing "and the id question is asked WITHOUT consulting the store"
      (is (nil? (facts/registered-client st {}))))))

(deftest provenance-distinguishes-unnamed-from-unregistered
  (let [st (fresh-store)]
    (is (empty? (facts/provenance-violations st {:client-id "C-1"})))
    (is (= #{:no-client} (rules (facts/provenance-violations st {:client-id "C-404"}))))
    (is (= #{:no-client-id} (rules (facts/provenance-violations st {:client-id ""}))))))

;; ── defect 1: the checks reach every citing op ────────────────────────────

(deftest product-checks-do-not-depend-on-which-op-asked
  ;; The repair for the bulk-order gap. These functions take the proposal and
  ;; never look at :op to decide WHETHER to run -- the governor decides that
  ;; from operation/cites-product?.
  (let [st (fresh-store)
        req {:client-id "C-1"}]
    (doseq [o [:approve-sale :approve-bulk-order]]
      (testing (str "for " o)
        (is (= #{:unknown-product}
               (rules (facts/product-violations st req {:op o :product-id "P-404"}))))
        (is (= #{:sale-without-product}
               (rules (facts/product-violations st req {:op o}))))
        (let [p (store/product st "P-1")]
          (is (= #{:off-label-claim}
                 (rules (facts/indication-violations p {:op o :claimed-indications #{"cancer-cure"}}))))
          (is (= #{:unlicensed-buyer}
                 (rules (facts/buyer-violations st req p {:op o :buyer-id "B-NOPE"})))))))))

(deftest product-wrong-client-is-its-own-rule
  (let [st (fresh-store)]
    (store/register-product! st {:product-id "P-9" :client-id "C-2"
                                 :approved-indications #{"anything"} :restricted? false})
    (is (= #{:product-wrong-client}
           (rules (facts/product-violations st {:client-id "C-1"}
                                            {:op :approve-sale :product-id "P-9"}))))))

;; ── the two hard invariants ───────────────────────────────────────────────

(deftest the-subset-invariant
  (let [p {:approved-indications #{"a" "b"}}]
    (testing "a subset is admitted, including the empty set and the full set"
      (is (empty? (facts/indication-violations p {:claimed-indications #{"a"}})))
      (is (empty? (facts/indication-violations p {:claimed-indications #{"a" "b"}})))
      (is (empty? (facts/indication-violations p {:claimed-indications #{}})))
      (is (empty? (facts/indication-violations p {}))))
    (testing "anything outside the registered set is off-label"
      (is (= #{:off-label-claim} (rules (facts/indication-violations p {:claimed-indications #{"c"}}))))
      (testing "including a claim that is PARTLY on-label"
        (is (= #{:off-label-claim}
               (rules (facts/indication-violations p {:claimed-indications #{"a" "c"}}))))))
    (testing "a product with no registered indications approves nothing"
      (is (= #{:off-label-claim}
             (rules (facts/indication-violations {} {:claimed-indications #{"a"}})))))))

(deftest a-bare-string-is-malformed-not-a-set-of-characters
  ;; Pre-change this refused for the right verdict and the wrong reason: it
  ;; reported every LETTER as an off-label claim.
  (let [p {:approved-indications #{"coronary-stenosis"}}
        vs (facts/indication-violations p {:claimed-indications "coronary-stenosis"})]
    (is (= #{:malformed-indications} (rules vs)))
    (is (not (re-find #"\\c" (:detail (first vs))))
        "the detail should name the shape problem, not enumerate characters")))

(deftest well-formed-indications-shape
  (is (true? (facts/well-formed-indications? nil)))
  (is (true? (facts/well-formed-indications? #{})))
  (is (true? (facts/well-formed-indications? #{"a"})))
  (is (true? (facts/well-formed-indications? ["a" "b"])))
  (doseq [x ["a" :a 42 #{"a" ""} #{"a" nil} #{:a}]]
    (is (false? (facts/well-formed-indications? x)) (str "accepted: " (pr-str x)))))

(deftest the-licensed-buyer-gate-is-conditional
  (let [st (fresh-store)
        req {:client-id "C-1"}
        restricted (store/product st "P-1")
        open-product (store/product st "P-2")]
    (testing "restricted: membership is required"
      (is (empty? (facts/buyer-violations st req restricted {:buyer-id "B-LICENSED"})))
      (is (= #{:unlicensed-buyer} (rules (facts/buyer-violations st req restricted {:buyer-id "B-NOPE"})))))
    (testing "unrestricted: no gate at all"
      ;; Without this case a governor that demanded a licence for EVERYTHING
      ;; would pass every other buyer test in this file.
      (is (empty? (facts/buyer-violations st req open-product {:buyer-id "B-NOPE"})))
      (is (empty? (facts/buyer-violations st req open-product {}))))
    (testing "a restricted sale naming nobody is told that, not that a buyer failed a check"
      (is (= #{:buyer-not-named} (rules (facts/buyer-violations st req restricted {}))))
      (is (= #{:buyer-not-named} (rules (facts/buyer-violations st req restricted {:buyer-id ""})))))
    (testing "another client's buyer roster does not answer for this client"
      (is (= #{:unlicensed-buyer}
             (rules (facts/buyer-violations st {:client-id "C-2"} restricted {:buyer-id "B-LICENSED"})))))))

;; ── vocabulary and actuation ──────────────────────────────────────────────

(deftest vocabulary-violations-name-which-kind-of-failure
  (is (empty? (facts/vocabulary-violations {:op :approve-sale})))
  (is (= #{:reserved-op} (rules (facts/vocabulary-violations {:op :grant-buyer-licence}))))
  (is (= #{:undeclared-op} (rules (facts/vocabulary-violations {:op :rush-the-order}))))
  (is (= #{:undeclared-op} (rules (facts/vocabulary-violations {:op nil}))))
  (testing "a reserved-op violation pins WHICH boundary was crossed"
    (is (= :grant-buyer-licence
           (:op (first (facts/vocabulary-violations {:op :grant-buyer-licence})))))))

(deftest actuation-violations
  (is (empty? (facts/actuation-violations {:effect :propose})))
  (doseq [e [:write :commit nil]]
    (is (= #{:no-actuation} (rules (facts/actuation-violations {:effect e}))))))

(deftest bulk-order-is-recognised-by-the-op-not-by-a-quantity-guess
  (is (true? (facts/bulk-order? {:op :approve-bulk-order})))
  (is (false? (facts/bulk-order? {:op :approve-sale})))
  (is (false? (facts/bulk-order? {}))))

(deftest every-violation-explains-itself
  ;; A rule keyword with no detail is a refusal an operator cannot act on.
  (let [st (fresh-store)
        all (concat (facts/provenance-violations st {})
                    (facts/vocabulary-violations {:op :rush-the-order})
                    (facts/actuation-violations {:effect :write})
                    (facts/confidence-violations {:confidence "high"})
                    (facts/product-violations st {:client-id "C-1"} {:op :approve-sale})
                    (facts/indication-violations {:approved-indications #{"a"}}
                                                 {:claimed-indications #{"z"}})
                    (facts/buyer-violations st {:client-id "C-1"}
                                            (store/product st "P-1") {:buyer-id "B-NOPE"}))]
    (is (= 7 (count all)) "expected one violation from each family")
    (doseq [v all]
      (is (keyword? (:rule v)))
      (is (string? (:detail v)) (str "no detail for " (:rule v)))
      (is (pos? (count (:detail v))) (str "empty detail for " (:rule v))))))
