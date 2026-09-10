(ns medsales.operation
  "The closed vocabulary of operations the ISCO-08 2433 technical & medical
  sales actor may propose.

  Runtime: portable `.cljc` (pure data + pure predicates, no host interop).

  Why this namespace exists. Before it, the operation vocabulary lived in two
  places that could not disagree loudly: the Advisor's docstring, which names
  two operations in prose, and the Governor's private `hard-violations`, which
  bound its product checks to a single keyword equality test,
  `(= :approve-sale op)`. Everything else the vocabulary did not mention was
  admitted. Measured on the pre-change tree, against the registered client
  `C-1`, with `:effect :propose` and `:confidence 0.95`:

      :register-product-indication => {:ok? true :hard? false :escalate? false} violations=[]
      :grant-buyer-licence         => {:ok? true :hard? false :escalate? false} violations=[]
      :pay-kickback-to-prescriber  => {:ok? true :hard? false :escalate? false} violations=[]
      :ship-controlled-substance   => {:ok? true :hard? false :escalate? false} violations=[]
      :sign-distribution-contract  => {:ok? true :hard? false :escalate? false} violations=[]
      :certify-clinical-efficacy   => {:ok? true :hard? false :escalate? false} violations=[]
      :delete-audit-trail          => {:ok? true :hard? false :escalate? false} violations=[]
      :disable-audit-ledger        => {:ok? true :hard? false :escalate? false} violations=[]
      nil                          => {:ok? true :hard? false :escalate? false} violations=[]

  All admitted, and admitted as *clean* verdicts: no escalation, no human, and
  an empty violation list to show a reviewer.

  Read the first two again. This actor's two HARD invariants are both
  set-membership questions: a claimed indication must be a member of the
  product's registered `:approved-indications`, and the buyer of a restricted
  product must be a member of the client's registered licensed-buyers. An
  actor that can propose `:register-product-indication` or
  `:grant-buyer-licence` can widen the very set it is being checked against —
  the subset test still passes, against a set the actor chose. A gate whose
  keys the applicant may cut is not a gate, and no amount of care inside
  `hard-violations` fixes it, because the defect is that the vocabulary was
  open in the first place.

  An actor whose operation set is open cannot be governed, because the governor
  is answering a question about a vocabulary nobody declared. So the vocabulary
  is declared here, once, as an allowlist, and `medsales.governor` refuses
  anything outside it.

  Two disjoint maps:

  * `supported` — what the actor may propose. `:escalates?` and
    `:cites-product?` are properties of the operation, not of the governor's
    mood, so they live beside it.
  * `reserved` — operations naming authority this cognitive actor does not
    hold: defining what a product is approved for, deciding who may buy a
    controlled one, inducing a prescriber, moving physical stock, binding the
    client contractually, making a clinical claim, and destroying the record.
    These are *declared* rather than merely absent so the refusal can say why.
    An undeclared op is a vocabulary error; a reserved op is an authority
    boundary. Conflating them would let a future edit `supported`-list one of
    them by accident."
  (:require [clojure.string :as str]))

(def supported
  "Operations the actor may propose.

  `:escalates?` true means human sign-off is required regardless of advisor
  confidence. `:cites-product?` true means the proposal binds to a REGISTERED
  product of this client, and must therefore satisfy the product, indication
  and buyer checks in `medsales.facts`.

  Only the two operations the Advisor's docstring has always claimed are
  listed. The vocabulary is deliberately not widened here: this namespace
  exists to close an opening, and adding ops would be the opposite of that.

  Note that BOTH supported ops cite a product, so there is currently no
  non-citing operation that could carry a `:product-id` past the citation
  checks. `medsales.facts` therefore has no mirror `citation-violations` guard,
  and this is stated rather than left implicit: adding a non-citing op to this
  map without writing that guard would open exactly the hole this namespace
  closed, one op over."
  {:approve-sale
   {:escalates?     false
    :cites-product? true
    :summary "approve a sale of a registered product to a named buyer"}

   :approve-bulk-order
   {:escalates?     true
    :cites-product? true
    :summary "approve a large-quantity order (elevated diversion risk; always human sign-off)"}})

(def reserved
  "Operations reserved to someone this actor is not. Naming one in a proposal
  is a permanent hard block, never an escalation: escalation would imply a
  human could approve the *actor* doing it, and neither the client nor its
  sales manager can delegate a regulatory approval, a controlled-substance
  licence, a clinical claim, or the destruction of an audit record to a remote
  cognitive actor.

  The first two are the ones that matter most here, because they are not merely
  outside the actor's authority — they are the authority that DEFINES the sets
  the actor's two hard invariants are checked against. Every entry below was
  measured walking through the pre-change governor clean; see this namespace's
  docstring for the measurements."
  {:register-product-indication
   {:reason "承認適応の登録は規制当局と製造販売業者の行為であって、販売側の結論ではない。これを actor が提案できると、部分集合検査が照合する集合そのものを actor が広げられる（計測 2026-09-10: 素通りしていた）"}

   :grant-buyer-licence
   {:reason "免許保有者名簿への追加は免許を発行する当局の行為。これを actor が提案できると、制限品目の買い手ゲートを actor 自身が開けられる（計測 2026-09-10: 素通りしていた）"}

   :pay-kickback-to-prescriber
   {:reason "処方者への金銭的誘因は販売手法ではなく、多くの法域で刑事罰の対象。人間の承認で正当化できる種類のものではない（計測 2026-09-10: 素通りしていた）"}

   :ship-controlled-substance
   {:reason "制限品目の物理的な出荷は提案ではなく実行であり、この認知 actor は :propose しか持たない（計測 2026-09-10: 素通りしていた）"}

   :sign-distribution-contract
   {:reason "販売契約の締結は client を法的に拘束する行為で、代理権を持たない（計測 2026-09-10: 素通りしていた）"}

   :certify-clinical-efficacy
   {:reason "臨床的有効性の証明はメディカルアフェアーズと規制の領域であって、販売の結論ではない（計測 2026-09-10: 素通りしていた）"}

   :delete-audit-trail
   {:reason "監査証跡の破棄は、事後の検証が依拠する証拠そのものを消すこと（計測 2026-09-10: 素通りしていた）"}

   :disable-audit-ledger
   {:reason "監査台帳を外すことは、この actor 自身の governance が依拠している証拠を消すこと（計測 2026-09-10: 素通りしていた）"}})

(defn supported? [op] (contains? supported op))
(defn reserved? [op] (contains? reserved op))

(defn declared?
  "True if `op` is named anywhere in this vocabulary. An op that is neither
  supported nor reserved is undeclared — the governor refuses it."
  [op]
  (or (supported? op) (reserved? op)))

(defn escalates?
  "True if the operation itself always requires human sign-off. Undeclared and
  reserved ops are hard-blocked before this is consulted, so a false here is
  not an admission."
  [op]
  (boolean (get-in supported [op :escalates?])))

(defn cites-product?
  "True if the operation binds to a registered product of this client and must
  therefore satisfy the product, indication and buyer checks. False for
  undeclared and reserved ops, which the governor hard-blocks first."
  [op]
  (boolean (get-in supported [op :cites-product?])))

(defn reserved-reason [op] (get-in reserved [op :reason]))

(defn label
  "A printable name for an op, including `nil` and non-keyword values. Exists
  because `(name nil)` throws, and the pre-change advisor called `name` on the
  request's op while building its rationale — so a request carrying `{:op nil}`
  died with a NullPointerException before the governor was ever consulted
  (measured 2026-09-10 through `medsales.actor/run-request!`). A crash is not a
  refusal: it leaves no verdict, no hold, and no ledger entry."
  [op]
  (cond
    (nil? op)     "nil"
    (keyword? op) (name op)
    (string? op)  op
    :else         (str op)))

(defn descriptor
  "The declared entry for `op`, or nil. Used by the governor's refusal message
  so an undeclared op is reported with the vocabulary it missed rather than
  with a bare false."
  [op]
  (or (get supported op) (get reserved op)))

(def vocabulary-summary
  "One line per declared op, for operators and for the sim report."
  (str "supported: " (str/join ", " (sort (map name (keys supported)))) "\n"
       "reserved:  " (str/join ", " (sort (map name (keys reserved))))))
