(ns medsales.sim
  "Deterministic governed-scenario harness for the ISCO-08 2433 technical &
  medical sales actor: run a table of requests through the real StateGraph and
  report which ones the governor refused.

  Runtime: `run` and `report` are portable `.cljc`. `-main` is `:clj`-only,
  because process exit codes are a host concern; the `:cljs` branch throws
  rather than pretending to exit.

  Why this namespace exists, and why it fails loudly. A governed actor's claim
  is not that it acts — it is that there exist actions it refuses. A harness
  that ran only clean scenarios would print green while demonstrating nothing,
  which is the shape this workspace has repeatedly caught: a check that could
  not fail returning the same value as a check that passed.

  So `run` counts refusals, and `-main` exits non-zero when the count is zero.
  A scenario table that has stopped exercising the governor is a defect in the
  table, and it is reported as one rather than as a pass.

  The four questions this harness answers that a unit test does not:
    * does the *wired graph* refuse, or only the pure `check` function
    * does it refuse for the reason it names, or for some other reason that
      happens to produce the same phase
    * does an escalated request actually interrupt rather than write
    * does the ledger it leaves behind verify, and does it record who approved
      each write

  The second one is why every refusal scenario carries `:because`, a violation
  rule that must appear in the verdict, and every admissible one carries
  `:clean?`, which asserts the violation list is empty. This table has a
  concrete instance of why that matters: the unregistered client, the
  unregistered product and the other client's product all reach `:hold`, so
  without `:because` a governor that had lost every provenance check but one
  would still show green on all three. It is also exactly how the pre-change
  empty-map-client defect hid — that request DID reach `:hold`, but for
  `:unknown-product`, a rule about a completely different question.

  Two mutations are NOT caught here, and naming them is more useful than
  implying the table is complete:

    * reversing the two clauses of `phase/of-verdict` — the governor does not
      emit a verdict that is both hard and escalating, so the ordering has no
      observable effect on any scenario. `medsales.phase-test` covers it.
    * unchaining `ledger/entry` (always hashing against prev 0) — almost every
      scenario here runs the graph once, and a single-entry ledger has prev 0
      legitimately. `medsales.ledger-test` covers it, and
      `actor-test/the-ledger-it-leaves-behind-verifies` runs the graph twice on
      one store, which is what makes the break observable.

  Every scenario marked `pre-change` below is one of the admissions measured on
  the pre-change tree — see the docstrings of `medsales.operation` and
  `medsales.facts` for those measurements. This table is the standing evidence
  that they are refusals now."
  (:require [medsales.actor :as actor]
            [medsales.advisor :as advisor]
            [medsales.ledger :as led]
            [medsales.phase :as phase]
            [medsales.store :as store]))

(def registered-client
  {:client-id "C-1" :name "Awai Medical Supply"})

(def other-client
  {:client-id "C-2" :name "Another distributor"})

(def registered-products
  "One restricted product, one unrestricted, and one belonging to the OTHER
  client. The restricted/unrestricted pair is what makes the licensed-buyer
  gate a CONDITIONAL membership test rather than a plain one — without the
  unrestricted product, a governor that demanded a licence for everything would
  pass every scenario in this table."
  [{:product-id "P-1" :client-id "C-1" :name "CardioStent"
    :approved-indications #{"coronary-stenosis"} :restricted? true}
   {:product-id "P-2" :client-id "C-1" :name "ExamGloves"
    :approved-indications #{"barrier-protection"} :restricted? false}
   {:product-id "P-9" :client-id "C-2" :name "Another firm's device"
    :approved-indications #{"anything"} :restricted? false}])

(def registered-buyers
  [["C-1" "B-LICENSED"]])

(defn- tweaking-advisor
  "An advisor that proposes as the mock does, then applies `f` to the proposal.
  Used to reach proposal shapes a well-formed request cannot produce — an
  unusable confidence, a direct write effect."
  [f]
  (let [inner (advisor/mock-advisor)]
    (reify advisor/Advisor
      (-advise [_ store request] (f (advisor/-advise inner store request))))))

(def scenarios
  "Each entry: the request, the phase it must reach, and why.

  `:expect` is the phase, not merely 'refused', so a scenario that starts
  holding for the wrong reason, or that escalates where it should hold, is a
  mismatch rather than a pass.

  `:because` is the violation rule that must appear in the verdict. Without it
  a scenario passes when the graph holds for any reason at all, which is the
  shape where a check stops discriminating without turning red.

  `:clients` and `:products` override the default registration for scenarios
  about the registration itself."
  [;; ── admissible ────────────────────────────────────────────────────────
   {:name :clean-sale-on-label-licensed-buyer
    :request {:client-id "C-1" :op :approve-sale :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-LICENSED"}
    :expect :commit
    :clean? true
    :why "on-label claim, restricted product, licensed buyer — the conforming sale"}

   {:name :clean-sale-unrestricted-no-buyer-licence-needed
    :request {:client-id "C-1" :op :approve-sale :product-id "P-2"
              :claimed-indications #{"barrier-protection"} :buyer-id "B-ANYONE"}
    :expect :commit
    :clean? true
    :why "the licensed-buyer gate is CONDITIONAL on :restricted?; an unrestricted product has no such gate"}

   {:name :clean-sale-claiming-no-indication
    :request {:client-id "C-1" :op :approve-sale :product-id "P-2"
              :buyer-id "B-ANYONE"}
    :expect :commit
    :clean? true
    :why "a sale that claims no indication claims nothing off-label; absent is well-formed"}

   ;; ── escalations: refusals to act without a human ──────────────────────
   {:name :bulk-order-always-escalates
    :request {:client-id "C-1" :op :approve-bulk-order :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-LICENSED"}
    :expect :request-approval
    :clean? true
    :why "elevated diversion risk is human sign-off even when every check passes"}

   {:name :low-confidence-escalates
    :request {:client-id "C-1" :op :approve-sale :product-id "P-2"
              :claimed-indications #{"barrier-protection"} :buyer-id "B-ANYONE"}
    :tweak #(assoc % :confidence 0.1)
    :expect :request-approval
    :clean? true
    :why "a usable but low confidence is a question for a human, not a block"}

   ;; ── the bulk-order gap: escalated with an EMPTY violation list ─────────
   {:name :bulk-order-off-label
    :request {:client-id "C-1" :op :approve-bulk-order :product-id "P-1"
              :claimed-indications #{"cancer-cure"} :buyer-id "B-LICENSED"}
    :because :off-label-claim
    :expect :hold
    :why "pre-change: escalated to a human carrying violations=[] — the SAME off-label claim was hard-blocked as :approve-sale"}

   {:name :bulk-order-unlicensed-buyer
    :request {:client-id "C-1" :op :approve-bulk-order :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-NOT-LICENSED"}
    :because :unlicensed-buyer
    :expect :hold
    :why "pre-change: the largest order of a controlled product to an unlicensed buyer escalated with nothing wrong shown"}

   {:name :bulk-order-unregistered-product
    :request {:client-id "C-1" :op :approve-bulk-order :product-id "P-404"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-LICENSED"}
    :because :unknown-product
    :expect :hold
    :why "pre-change: escalated clean; product basis reached :approve-sale only"}

   {:name :bulk-order-another-clients-product
    :request {:client-id "C-1" :op :approve-bulk-order :product-id "P-9"
              :claimed-indications #{"anything"} :buyer-id "B-LICENSED"}
    :because :product-wrong-client
    :expect :hold
    :why "pre-change: escalated clean; another firm's product is not this client's to sell"}

   ;; ── vocabulary: measured as admitted-clean before this change ─────────
   {:name :reserved-op-register-product-indication
    :request {:client-id "C-1" :op :register-product-indication :product-id "P-1"}
    :because :reserved-op
    :expect :hold
    :why "pre-change: the actor could propose widening the very set the subset check compares against"}

   {:name :reserved-op-grant-buyer-licence
    :request {:client-id "C-1" :op :grant-buyer-licence :buyer-id "B-NOT-LICENSED"}
    :because :reserved-op
    :expect :hold
    :why "pre-change: the actor could propose opening the licensed-buyer gate it is checked against"}

   {:name :reserved-op-pay-kickback-to-prescriber
    :request {:client-id "C-1" :op :pay-kickback-to-prescriber}
    :because :reserved-op
    :expect :hold
    :why "pre-change: admitted as a clean verdict — no escalation, no human, empty violation list"}

   {:name :reserved-op-ship-controlled-substance
    :request {:client-id "C-1" :op :ship-controlled-substance :product-id "P-1"}
    :because :reserved-op
    :expect :hold
    :why "pre-change: physical dispatch is execution, not proposal, and was admitted clean"}

   {:name :reserved-op-delete-audit-trail
    :request {:client-id "C-1" :op :delete-audit-trail}
    :because :reserved-op
    :expect :hold
    :why "pre-change: destroying the evidence the governance rests on was admitted clean"}

   {:name :undeclared-op
    :request {:client-id "C-1" :op :rush-the-order :product-id "P-1"}
    :because :undeclared-op
    :expect :hold
    :why "pre-change: an op nobody declared was admitted as a clean verdict"}

   {:name :nil-op
    :request {:client-id "C-1" :op nil}
    :because :undeclared-op
    :expect :hold
    :why "pre-change: (name nil) threw in the advisor, so the run died before the governor was consulted"}

   ;; ── provenance ────────────────────────────────────────────────────────
   {:name :unregistered-client
    :request {:client-id "C-404" :op :approve-sale :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-LICENSED"}
    :because :no-client
    :expect :hold
    :why "client provenance"}

   {:name :request-without-client-id
    :clients [{}]
    :request {:op :approve-sale :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-LICENSED"}
    :because :no-client-id
    :expect :hold
    :why "pre-change: the empty-map client landed under the nil key and answered this request; it held for :unknown-product, a different question"}

   ;; ── confidence ────────────────────────────────────────────────────────
   {:name :unusable-confidence-non-numeric
    :request {:client-id "C-1" :op :approve-sale :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-LICENSED"}
    :tweak #(assoc % :confidence "high")
    :because :unusable-confidence
    :expect :hold
    :why "pre-change: threw ClassCastException on clj, admitted clean on cljs — same file, opposite verdicts"}

   {:name :unusable-confidence-above-one
    :request {:client-id "C-1" :op :approve-sale :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-LICENSED"}
    :tweak #(assoc % :confidence 99.0)
    :because :unusable-confidence
    :expect :hold
    :why "pre-change: a floor with no ceiling let 99.0 buy out of escalation"}

   ;; ── the two hard invariants, on the op that always had them ───────────
   {:name :sale-off-label
    :request {:client-id "C-1" :op :approve-sale :product-id "P-1"
              :claimed-indications #{"cancer-cure"} :buyer-id "B-LICENSED"}
    :because :off-label-claim
    :expect :hold
    :why "the subset invariant — this one the pre-change governor did catch, and it must stay caught now the mechanism changed"}

   {:name :sale-unlicensed-buyer
    :request {:client-id "C-1" :op :approve-sale :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-NOT-LICENSED"}
    :because :unlicensed-buyer
    :expect :hold
    :why "the conditional-membership invariant — also caught pre-change, and must stay caught"}

   {:name :restricted-sale-naming-no-buyer
    :request {:client-id "C-1" :op :approve-sale :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"}}
    :because :buyer-not-named
    :expect :hold
    :why "a restricted sale that names nobody failed pre-change as :unlicensed-buyer, telling the reviewer a buyer failed a check never run against anybody"}

   {:name :malformed-indications-bare-string
    :request {:client-id "C-1" :op :approve-sale :product-id "P-1"
              :claimed-indications "coronary-stenosis" :buyer-id "B-LICENSED"}
    :because :malformed-indications
    :expect :hold
    :why "pre-change: (set \"coronary-stenosis\") is a set of CHARACTERS, so it refused for the right verdict and reported every letter as an off-label claim"}

   {:name :sale-citing-no-product
    :request {:client-id "C-1" :op :approve-sale
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-LICENSED"}
    :because :sale-without-product
    :expect :hold
    :why "a sale approval that names no product is not a sale of anything"}

   ;; ── actuation ─────────────────────────────────────────────────────────
   {:name :direct-write-effect
    :request {:client-id "C-1" :op :approve-sale :product-id "P-1"
              :claimed-indications #{"coronary-stenosis"} :buyer-id "B-LICENSED"}
    :tweak #(assoc % :effect :write)
    :because :no-actuation
    :expect :hold
    :why "the advisor proposes; it never writes"}])

(defn- seeded-store [scenario]
  (let [st (store/mem-store)]
    (doseq [c (:clients scenario [registered-client other-client])]
      (store/register-client! st c))
    (doseq [p (:products scenario registered-products)]
      (store/register-product! st p))
    (doseq [[c b] (:buyers scenario registered-buyers)]
      (store/register-licensed-buyer! st c b))
    st))

(defn- run-one [scenario]
  (let [st (seeded-store scenario)
        graph (actor/build-graph
               (cond-> {:store st}
                 (:tweak scenario) (assoc :advisor (tweaking-advisor (:tweak scenario)))))
        thread (str "sim-" (name (:name scenario)))
        result (actor/run-request! graph (:request scenario) {} thread)
        state (:state result)
        actual (or (:disposition state)
                   ;; A run that never reached :decide produced no phase at
                   ;; all; report that rather than defaulting it to a phase,
                   ;; which would make an unrun scenario look like a verdict.
                   :no-phase)
        rules (into #{} (map :rule) (:violations (:verdict state)))
        ;; A scenario with neither :because nor :clean? asserts nothing about
        ;; WHY, so it is reported as unreasoned rather than silently passing.
        reasoned? (or (contains? scenario :because) (:clean? scenario))
        because-ok? (cond
                      (:because scenario) (contains? rules (:because scenario))
                      (:clean? scenario)  (empty? rules)
                      :else false)]
    {:name (:name scenario)
     :expect (:expect scenario)
     :actual actual
     :why (:why scenario)
     :status (:status result)
     :because (:because scenario)
     :rules rules
     :reasoned? reasoned?
     :because-ok? because-ok?
     :match? (and (= actual (:expect scenario)) because-ok?)
     :phase-match? (= actual (:expect scenario))
     :refusal? (and (not= actual :no-phase) (phase/refusal? actual))
     :wrote? (pos? (count (store/records-of st (:client-id (:request scenario)))))
     :ledger-verify (led/verify (store/ledger st))}))

(defn run
  "Run every scenario. Returns
  `{:results [..] :refusals n :mismatches [..] :ledger-breaks [..] :ok? bool}`.

  `:ok?` requires five things: every scenario reached its expected phase FOR
  THE REASON IT NAMES, every scenario names a reason at all, no refusal wrote a
  record anyway, every ledger left behind verifies, and at least one refusal was
  demonstrated."
  []
  (let [results (mapv run-one scenarios)
        refusals (count (filter :refusal? results))
        mismatches (filterv (complement :match?) results)
        unreasoned (filterv (complement :reasoned?) results)
        ;; A refusal that still wrote a record is the worst outcome available
        ;; and would otherwise hide inside a matching phase.
        wrote-anyway (filterv #(and (:refusal? %) (:wrote? %)) results)
        ledger-breaks (filterv #(not (:ok? (:ledger-verify %))) results)]
    {:results results
     :refusals refusals
     :mismatches mismatches
     :unreasoned unreasoned
     :wrote-anyway wrote-anyway
     :ledger-breaks ledger-breaks
     :ok? (and (empty? mismatches)
               (empty? unreasoned)
               (empty? wrote-anyway)
               (empty? ledger-breaks)
               (pos? refusals))}))

(defn report
  "Human-readable run report. Pure: takes the result of `run`."
  [{:keys [results refusals mismatches unreasoned wrote-anyway ledger-breaks ok?]}]
  (str
   "medsales.sim — governed scenario run\n"
   (apply str
          (for [r results]
            (str "  " (if (:match? r) "ok  " "BAD ")
                 (name (:name r))
                 " expect=" (name (:expect r))
                 " actual=" (name (:actual r))
                 (when (:refusal? r) " [refused]")
                 (cond
                   (not (:reasoned? r)) " NO-REASON-DECLARED"
                   (:because-ok? r) (if (:because r)
                                      (str " because=" (name (:because r)))
                                      " clean")
                   :else (str " WRONG-REASON want=" (pr-str (:because r))
                              " got=" (pr-str (:rules r))))
                 "\n")))
   "  scenarios=" (count results)
   " refusals=" refusals
   " mismatches=" (count mismatches)
   " unreasoned=" (count unreasoned)
   " wrote-anyway=" (count wrote-anyway)
   " ledger-breaks=" (count ledger-breaks)
   "\n"
   (cond
     (zero? refusals)
     "  REFUSING TO REPORT A PASS: the scenario table demonstrated no refusal.\n"
     ok? "  PASS\n"
     :else "  FAIL\n")))

#?(:clj
   (defn -main [& _]
     (let [r (run)]
       (print (report r))
       (flush)
       (System/exit (if (:ok? r) 0 1))))
   :cljs
   (defn -main [& _]
     (throw (ex-info "medsales.sim/-main is :clj-only (process exit codes are a host concern); call `run` and inspect the result instead" {}))))
