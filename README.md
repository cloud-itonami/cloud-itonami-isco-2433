# cloud-itonami-isco-2433

Open Business Blueprint for **ISCO-08 2433**: Technical and Medical Sales Professionals (excluding ICT) — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — TechnicalMedicalSalesAdvisor ⊣
TechnicalMedicalSalesGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
13 tests / 27 assertions green.

The medical-sales HARD invariants — subset containment and
conditional membership, not sales technique:

1. **Indication subset** — the proposed claimed-indications set must
   be a subset of the product's registered approved-indications set.
   Off-label marketing is a subset violation, not a sales technique.
2. **Licensed-buyer gate** — if the product is registered restricted,
   the buyer must be a member of the client's registered
   licensed-buyers set (unrestricted products have no such gate).

Also HARD: unregistered/foreign product, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:approve-bulk-order` (large-quantity order, elevated diversion risk),
low confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
