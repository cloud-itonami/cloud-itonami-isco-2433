(ns medsales.ledger-test
  "Unit tests for the append-only audit ledger.

  The chaining property is the one `medsales.sim` cannot see: almost every
  scenario runs the graph once, and a single-entry ledger legitimately has
  `:ledger/prev 0`, so a `ledger/entry` that always hashed against 0 would
  leave the whole scenario table green. Reordering and tampering are checked
  here on ledgers built by hand."
  (:require [clojure.test :refer [deftest is testing]]
            [medsales.ledger :as led]))

(defn- three []
  (-> []
      (led/append {:disposition :commit :approved-by :actor :record {:op :approve-sale}})
      (led/append {:disposition :hold :approved-by :none :verdict {:hard? true}})
      (led/append {:disposition :commit :approved-by :human :record {:op :approve-bulk-order}})))

(deftest a-well-formed-chain-verifies
  (let [l (three)]
    (is (= 3 (count l)))
    (is (= [0 1 2] (mapv :ledger/seq l)))
    (is (:ok? (led/verify l)))
    (is (= 3 (:length (led/verify l))))))

(deftest each-entry-commits-to-its-predecessor
  (let [l (three)]
    (is (= 0 (:ledger/prev (first l))))
    (is (= (:ledger/hash (first l)) (:ledger/prev (second l))))
    (is (= (:ledger/hash (second l)) (:ledger/prev (nth l 2))))))

(deftest tampering-with-content-breaks-the-chain
  (testing "changing who approved a write is detected"
    ;; This is the tamper that matters: the ledger's whole purpose here is to
    ;; show that a human signed the high-diversion-risk order.
    (let [l (three)
          forged (assoc-in (vec l) [2 :approved-by] :actor)
          v (led/verify forged)]
      (is (false? (:ok? v)))
      (is (= 2 (:broken-at v)))
      (is (= :hash-mismatch (:reason v)))))
  (testing "changing a committed record is detected"
    (let [l (three)
          forged (assoc-in (vec l) [0 :record :op] :approve-bulk-order)
          v (led/verify forged)]
      (is (false? (:ok? v)))
      (is (= 0 (:broken-at v)))
      (is (= :hash-mismatch (:reason v))))))

(deftest reordering-is-detected
  (let [l (vec (three))
        swapped [(nth l 0) (nth l 2) (nth l 1)]
        v (led/verify swapped)]
    (is (false? (:ok? v)))
    (is (= :seq-mismatch (:reason v)))))

(deftest dropping-a-middle-entry-is-detected
  (let [l (vec (three))
        gapped [(nth l 0) (nth l 2)]
        v (led/verify gapped)]
    (is (false? (:ok? v)))
    (is (= :seq-mismatch (:reason v)))))

(deftest an-unchained-entry-is-detected
  (testing "an entry hashed against prev 0 instead of its predecessor"
    ;; This is exactly the mutation the sim cannot see.
    (let [l (vec (three))
          unchained (assoc (nth l 1) :ledger/prev 0)
          forged (assoc l 1 unchained)
          v (led/verify forged)]
      (is (false? (:ok? v)))
      (is (= 1 (:broken-at v)))
      (is (= :prev-mismatch (:reason v))))))

(deftest truncation-is-NOT-detected-and-that-is-stated
  (testing "a chain cannot detect entries it never saw; verify claims only what it can show"
    (let [l (vec (three))
          truncated (subvec l 0 2)]
      (is (:ok? (led/verify truncated)))
      (is (= 2 (:length (led/verify truncated))))))
  (testing "an empty ledger verifies"
    (is (:ok? (led/verify [])))))

(deftest the-hash-is-deterministic-and-position-dependent
  (is (= (led/chain-hash 0 {:a 1}) (led/chain-hash 0 {:a 1})))
  (is (not= (led/chain-hash 0 {:a 1}) (led/chain-hash 1 {:a 1})))
  (is (not= (led/chain-hash 0 {:a 1}) (led/chain-hash 0 {:a 2})))
  (testing "the hash stays inside the exactly-representable integer range on both hosts"
    (is (< (led/chain-hash 2147483646 {:a "some content"}) 2147483647))
    (is (>= (led/chain-hash 2147483646 {:a "some content"}) 0))))

(deftest commit-entries-record-who-approved-them
  (testing "the distinction the pre-change ledger could not make"
    (let [auto (led/commit-entry {:op :approve-sale} :actor)
          human (led/commit-entry {:op :approve-bulk-order} :human)]
      (is (= :actor (:approved-by auto)))
      (is (= :human (:approved-by human)))
      (testing "both carry the key, so an absent field cannot be mistaken for an unaudited one"
        (is (contains? auto :approved-by))
        (is (contains? human :approved-by)))))
  (testing "a hold records no approver at all"
    (is (= :none (:approved-by (led/hold-entry {:hard? true}))))))

(deftest summary-names-the-approver-for-every-entry
  (let [s (led/summary (three))]
    (is (re-find #"approved-by=actor" s))
    (is (re-find #"approved-by=human" s))
    (is (re-find #"approved-by=none" s))))
