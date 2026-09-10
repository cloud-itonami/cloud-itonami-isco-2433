(ns medsales.phase-test
  "Unit tests for the verdict -> phase routing.

  These exist because `medsales.sim` CANNOT discriminate the property that
  matters most here. The governor computes `:escalate?` as `(and (not hard?)
  ...)`, so it never emits a verdict carrying both flags, and reversing the two
  clauses of `phase/of-verdict` leaves the entire scenario table green. The
  ordering is the second of two independent guards, and this is the only place
  it is actually checked."
  (:require [clojure.test :refer [deftest is testing]]
            [medsales.phase :as phase]))

(deftest hard-beats-escalate
  (testing "a verdict that is BOTH hard and escalating must hold, not escalate"
    ;; Escalating it would ask a human to approve the actor doing something no
    ;; human can authorise it to do -- granting a buyer licence, shipping a
    ;; controlled substance -- while the request also happens to be a bulk
    ;; order. This shape is not emitted by the current governor; the guard is
    ;; here for callers that build a verdict by hand and for a governor that
    ;; stops zeroing the flag.
    (is (= :hold (phase/of-verdict {:hard? true :escalate? true})))
    (is (= :hold (phase/of-verdict {:hard? true :escalate? false})))))

(deftest routes-each-verdict
  (is (= :request-approval (phase/of-verdict {:hard? false :escalate? true})))
  (is (= :commit (phase/of-verdict {:hard? false :escalate? false})))
  (testing "a verdict missing both flags is a clean one"
    (is (= :commit (phase/of-verdict {})))))

(deftest what-each-phase-may-do
  (is (true? (phase/writes? :commit)))
  (is (false? (phase/writes? :hold)))
  (testing "an escalated proposal has NOT been written; it is waiting on a human"
    (is (false? (phase/writes? :request-approval)))
    (is (true? (phase/human-required? :request-approval))))
  (testing "an unknown phase writes nothing rather than defaulting to writing"
    (is (false? (phase/writes? :some-phase-nobody-declared)))))

(deftest both-non-writing-phases-count-as-refusals
  ;; medsales.sim counts these. If :request-approval stopped counting, a run
  ;; whose only refusals were escalations would report zero and the harness
  ;; would refuse to report a pass -- which is the intended direction, but the
  ;; classification itself is asserted here rather than inferred from that.
  (is (true? (phase/refusal? :hold)))
  (is (true? (phase/refusal? :request-approval)))
  (is (false? (phase/refusal? :commit))))

(deftest approval-provenance-is-derived-from-the-disposition
  (testing "a commit reached from :request-approval was signed by a human"
    (is (true? (phase/approved-commit? :request-approval))))
  (testing "a commit reached directly was not"
    (is (false? (phase/approved-commit? :commit)))
    (is (false? (phase/approved-commit? nil)))))

(deftest escalating-ops-are-declared-not-guessed
  (is (true? (phase/escalating-op? :approve-bulk-order)))
  (is (false? (phase/escalating-op? :approve-sale)))
  (testing "a reserved op does not report as escalating -- it is hard-blocked first"
    (is (false? (phase/escalating-op? :grant-buyer-licence)))))
