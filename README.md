# cloud-itonami-isco-2433

Open Business Blueprint for **ISCO-08 2433**: Technical and Medical Sales Professionals (excluding ICT) — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — TechnicalMedicalSalesAdvisor ⊣
TechnicalMedicalSalesGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.

```text
:intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                           +-> :request-approval   (:escalate? true, interrupt-before)
                                           +-> :hold               (:hard? true)
```

## The invariants

The medical-sales HARD invariants are subset containment and
conditional membership, not sales technique:

1. **Indication subset** — the proposed claimed-indications set must
   be a subset of the product's registered approved-indications set.
   Off-label marketing is a subset violation, not a sales technique.
2. **Licensed-buyer gate** — if the product is registered restricted,
   the buyer must be a member of the client's registered
   licensed-buyers set (unrestricted products have no such gate).

Also HARD: a request naming no client or an unregistered one, an
undeclared or **reserved** op, a non-`:propose` effect, a
`:confidence` outside `[0,1]`, and a citing op naming a product that
is unregistered or another client's. Escalations (always human
sign-off): `:approve-bulk-order` (large-quantity order, elevated
diversion risk), low confidence (< 0.6).

Both hard invariants are membership tests against **registered sets**,
so the two operations that would widen those sets —
`:register-product-indication` and `:grant-buyer-licence` — are
reserved rather than merely absent. An actor that can propose widening
the set it is checked against is not gated by it.

## Components

- `src/medsales/store.kotoba` — `Store` protocol + `MemStore`: registered
  clients, registered products (`:approved-indications`,
  `:restricted?`), registered licensed buyers, committed sale records,
  and a hash-chained append-only audit ledger.
- `src/medsales/advisor.kotoba` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) and `llm-advisor`. Either way the advisor
  only ever produces a `:propose`-effect proposal, and LLM parse
  failures yield `confidence 0.0` — forced escalation, never
  fabricated confidence.
- `src/medsales/operation.kotoba` — the **closed vocabulary**. `supported`
  is the allowlist of what the actor may propose; `reserved` names
  authority it does not hold (defining an approved indication, granting
  a buyer licence, inducing a prescriber, shipping stock, binding the
  client contractually, making a clinical claim, destroying the record)
  with a stated reason for each. An op in neither map is refused as
  `:undeclared-op`.
- `src/medsales/facts.kotoba` — one named, pure predicate per question the
  governor asks, each testable without building a graph.
- `src/medsales/governor.kotoba` — `check`: a pure function wired as its
  own `:govern` node. It holds the ORDER of the questions; each
  question lives in `facts`, over the vocabulary in `operation`.
- `src/medsales/phase.kotoba` — the verdict → phase routing, and what each
  phase may do. `:hard?` is checked before `:escalate?`: a proposal
  that is both must hold, because escalating it would ask a human to
  approve something they cannot authorise.
- `src/medsales/ledger.kotoba` — entry construction, hash chaining and
  verification. Every write records **who approved it** (`:actor` or
  `:human`), which is what the interrupt exists to establish.
- `src/medsales/actor.kotoba` — the compiled `StateGraph`.
- `src/medsales/sim.kotoba` — the governed-scenario harness.

## Running it

```bash
clojure -M:test    # unit tests
clojure -M:sim     # governed scenario run
clojure -M:lint    # clj-kondo, errors fail
```

`clojure -M:sim` runs every scenario through the real StateGraph and
reports which the governor refused. It **exits non-zero when the table
demonstrates no refusal at all**: a governed actor's claim is not that
it acts, it is that there exist actions it refuses, so a table that has
stopped refusing is a defect in the table rather than a pass. Each
refusal scenario names the violation rule that must appear in the
verdict, so a scenario that starts holding for an unrelated reason is a
mismatch rather than a pass.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
