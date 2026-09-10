(ns medsales.governor
  "TechnicalMedicalSalesGovernor — the independent safety/traceability layer
  for the ISCO-08 2433 community technical & medical sales (excluding ICT)
  actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Medical-sales twist: a claimed indication set must be a SUBSET of the
  registered approved-indications set — off-label marketing is a subset
  violation, not a sales technique — and a restricted product may only be sold
  to a REGISTERED licensed buyer.

  This namespace is now the ORDERING of the checks, not their content. Each
  question is a named pure function in `medsales.facts`, the operation
  vocabulary is a closed allowlist in `medsales.operation`, and the
  verdict-to-phase routing is in `medsales.phase`. The reason for the split is
  recorded in those namespaces: as one inline `cond->`, the checks had no names
  a test could hold, and four of them were measurably wrong (2026-09-10).

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance  — the request must NAME a client (`:no-client-id`)
                            and that client must be registered (`:no-client`).
    2. declared vocabulary — the op must be in `operation/supported`.
                            `:reserved-op` names an authority boundary,
                            `:undeclared-op` a word nobody defined.
    3. no-actuation       — proposal :effect must be :propose.
    4. usable confidence  — :confidence must be a number in [0,1].
    5. product basis      — a citing op must cite a REGISTERED product
                            belonging to this client.
    6. indication subset  — the proposed claimed-indications set must be a
                            subset of the product's registered
                            :approved-indications set.
    7. licensed-buyer gate — if the product is :restricted?, the proposed
                            buyer-id must be a member of the client's
                            registered licensed-buyers set.
  ESCALATION invariants (:escalate? true, human sign-off):
    8. :op :approve-bulk-order (large-quantity order, elevated diversion risk).
    9. low confidence (< `medsales.facts/confidence-floor`).

  Invariants 5-7 previously reached `:approve-sale` only. They now reach every
  op declaring `:cites-product?`, which is both supported ops — see
  `medsales.facts`, defect 1, for what the bulk-order gap did to the human
  being asked to sign off."
  (:require [medsales.facts :as facts]
            [medsales.operation :as op]
            [medsales.store :as store]))

;; Re-exported so existing callers and tests that read
;; `medsales.governor/confidence-floor` keep working; the value itself lives
;; beside the predicate that uses it.
(def confidence-floor facts/confidence-floor)

(defn- hard-violations
  "Every hard violation, in report order. Structured as successive stages
  rather than one `cond->` so that a later stage can rely on an earlier one
  having passed: the product checks are skipped when the op is not a citing op,
  and the indication and buyer checks are skipped when the product itself is
  the problem, so an unregistered product is not ALSO reported as claiming
  everything off-label and as having an unlicensed buyer.

  That skipping is why the pre-change unregistered-client case reported
  `[:no-client :product-wrong-client :unlicensed-buyer]` — three rules for one
  fact, two of them derived from a nil product record."
  [store request proposal]
  (let [base (into (into (into (vec (facts/provenance-violations store request))
                               (facts/vocabulary-violations proposal))
                         (facts/actuation-violations proposal))
                   (facts/confidence-violations proposal))]
    (if (seq base)
      base
      ;; Only reached when the request names a registered client, the op is
      ;; supported, the effect is :propose and the confidence is usable.
      (if-not (op/cites-product? (:op proposal))
        []
        (let [pv (facts/product-violations store request proposal)]
          (if (seq pv)
            pv
            (let [product (store/product store (:product-id proposal))]
              (into (vec (facts/indication-violations product proposal))
                    (facts/buyer-violations store request product proposal)))))))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `medsales.store/Store`. Pure — never mutates the store.

  Returns `{:ok? :violations :confidence :hard? :escalate?}`. `:escalate?` is
  computed as `(and (not hard?) ...)`, so a hard-blocked proposal never also
  escalates; `medsales.phase/of-verdict` holds the same ordering independently
  for callers that build a verdict by hand."
  [request _context proposal store]
  (let [hard  (hard-violations store request proposal)
        hard? (boolean (seq hard))
        conf  (:confidence proposal)
        low?  (facts/low-confidence? conf)
        risky-op? (facts/bulk-order? proposal)]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
