(ns medsales.actor-test
  (:require [clojure.test :refer [deftest is]]
            [medsales.actor :as actor]
            [medsales.ledger :as led]
            [medsales.sim :as sim]
            [medsales.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-product! st {:product-id "P-1" :client-id "client-1"
                                 :name "surgical-implant-x"
                                 :approved-indications #{"joint-replacement"}
                                 :restricted? true})
    (store/register-licensed-buyer! st "client-1" "B-LICENSED")
    st))

(defn- sale-request [indications buyer]
  {:client-id "client-1" :op :approve-sale :stake :low
   :product-id "P-1" :claimed-indications indications :buyer-id buyer})

(deftest commits-an-on-label-licensed-sale
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-sale :stake :low
                 :product-id "P-1" :claimed-indications #{"joint-replacement"}
                 :buyer-id "B-LICENSED"}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-off-label-sale
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-sale :stake :low
                 :product-id "P-1" :claimed-indications #{"cosmetic-use"}
                 :buyer-id "B-LICENSED"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-approves-bulk-order-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-bulk-order :stake :high
                 :product-id "P-1" :claimed-indications #{"joint-replacement"}
                 :buyer-id "B-LICENSED"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))

;; ── added 2026-09-10 with the governed components ─────────────────────────

(deftest the-ledger-it-leaves-behind-verifies
  ;; Two runs on ONE store, which is what makes an unchained ledger/entry
  ;; observable: a single-entry ledger legitimately has :ledger/prev 0.
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})]
    (actor/run-request! graph (sale-request #{"joint-replacement"} "B-LICENSED") {} "t-1")
    (actor/run-request! graph (assoc (sale-request #{"off-label-thing"} "B-LICENSED")
                                     :product-id "P-1")
                        {} "t-2")
    (let [l (store/ledger st)
          v (led/verify l)]
      (is (= 2 (count l)))
      (is (:ok? v) (str "ledger did not verify: " (pr-str v)))
      (is (= [0 1] (mapv :ledger/seq l)))
      (is (= (:ledger/hash (first l)) (:ledger/prev (second l)))
          "the second entry must commit to the first"))))

(deftest the-ledger-distinguishes-a-human-approved-write-from-an-automatic-one
  ;; The measured pre-change gap: both wrote {:disposition :commit :record ...}
  ;; with nothing saying whether a person signed it.
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})]
    (actor/run-request! graph (sale-request #{"joint-replacement"} "B-LICENSED") {} "t-auto")
    (let [r (actor/run-request! graph (assoc (sale-request #{"joint-replacement"} "B-LICENSED")
                                            :op :approve-bulk-order)
                                {} "t-esc")]
      (is (= :interrupted (:status r)) "a bulk order must interrupt, not write")
      (is (= 1 (count (store/ledger st))) "the interrupt must not have written a ledger entry yet")
      (actor/approve! graph "t-esc"))
    (let [l (store/ledger st)]
      (is (= 2 (count l)))
      (is (= [:actor :human] (mapv :approved-by l))
          "the automatic commit and the human-approved commit must be distinguishable")
      (is (:ok? (led/verify l))))))

(deftest a-refusal-writes-a-hold-entry-and-no-record
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})]
    (actor/run-request! graph (sale-request #{"cosmetic-enhancement"} "B-LICENSED") {} "t-hold")
    (is (empty? (store/records-of st "client-1")) "a held proposal must not be written")
    (let [l (store/ledger st)]
      (is (= 1 (count l)))
      (is (= :hold (:disposition (first l))))
      (is (= :none (:approved-by (first l))))
      (is (seq (:violations (:verdict (first l))))
          "the hold entry must carry the violations, so the ledger explains itself")
      (is (:ok? (led/verify l))))))

(deftest a-nil-op-reaches-a-verdict-instead-of-crashing
  ;; Pre-change this threw NullPointerException in the advisor's (name op),
  ;; before the governor was consulted. A crash is not a refusal: it leaves no
  ;; verdict, no hold and no ledger entry.
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        r (actor/run-request! graph {:client-id "client-1" :op nil} {} "t-nil")]
    (is (= :hold (:disposition (:state r))))
    (is (contains? (into #{} (map :rule) (:violations (:verdict (:state r)))) :undeclared-op))
    (is (= 1 (count (store/ledger st))))))

(deftest the-sim-scenario-table-demonstrates-refusals
  ;; The harness itself, run in-process. `run` requires that every scenario
  ;; reached its expected phase FOR THE REASON IT NAMES, that no refusal wrote
  ;; a record anyway, that every ledger verifies, and that at least one refusal
  ;; happened at all.
  (let [r (sim/run)]
    (is (pos? (:refusals r)) "a table that demonstrates no refusal proves nothing")
    (is (empty? (:mismatches r)) (str "mismatched scenarios: " (pr-str (mapv :name (:mismatches r)))))
    (is (empty? (:unreasoned r)) (str "scenarios asserting no reason: " (pr-str (mapv :name (:unreasoned r)))))
    (is (empty? (:wrote-anyway r)) (str "refusals that wrote anyway: " (pr-str (mapv :name (:wrote-anyway r)))))
    (is (empty? (:ledger-breaks r)) (str "ledgers that did not verify: " (pr-str (mapv :name (:ledger-breaks r)))))
    (is (:ok? r))))
