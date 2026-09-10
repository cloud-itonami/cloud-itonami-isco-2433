(ns medsales.facts
  "Shape predicates and violation functions for the ISCO-08 2433 technical &
  medical sales actor: the questions the Governor asks, each one named, pure,
  and testable without building a graph.

  Runtime: portable `.cljc`. Every predicate here is written so that Clojure
  and ClojureScript agree on the answer, which is not automatic — see
  `usable-confidence?`.

  Why this namespace exists. The Governor's checks were an inline `cond->` over
  a proposal map, so each one could only be exercised by calling `check` and
  reading a violation list, and none of them had a name a test could hold.
  Four separate defects lived in that shape, all measured on the pre-change
  tree against the registered client `C-1`, its registered restricted product
  `P-1` (`:approved-indications #{\"coronary-stenosis\"}`) and its one
  registered licensed buyer `B-LICENSED`.

  1. THE HIGHER-RISK OPERATION GOT FEWER CHECKS. Every product check was
     guarded by `approve?`, defined as `(= :approve-sale op)`. The other
     supported op, `:approve-bulk-order` — which the README itself describes as
     a `large-quantity order, elevated diversion risk` — matched none of them:

         {:op :approve-bulk-order :product-id \"P-1\"
          :claimed-indications #{\"cancer-cure\"} :buyer-id \"B-LICENSED\"}
         => {:ok? false :hard? false :escalate? true} violations=[]

         {:op :approve-bulk-order :product-id \"P-1\"
          :claimed-indications #{\"coronary-stenosis\"} :buyer-id \"B-NOT-LICENSED\"}
         => {:ok? false :hard? false :escalate? true} violations=[]

         {:op :approve-bulk-order :product-id \"P-404\" ...}
         => {:ok? false :hard? false :escalate? true} violations=[]

     while the identical off-label claim and the identical unlicensed buyer,
     submitted as `:approve-sale`, were hard-blocked with `[:off-label-claim]`
     and `[:unlicensed-buyer]`.

     Look at what that pair of outcomes does to a human. The bulk order was not
     admitted — it escalated, which reads like the safe path. But it escalated
     carrying an EMPTY violation list. The reviewer signing off on the
     largest-quantity order of a controlled product, to a buyer holding no
     licence, on an indication it was never approved for, was shown a proposal
     with nothing wrong with it. Escalation without the checks is worse than no
     escalation, because it puts a person's name on a verdict the machine never
     computed.

     The repair is not a wider `approve?`. It is that the property belongs to
     the operation — `medsales.operation/cites-product?` — so an op cannot be
     added without answering whether it cites a product.

  2. THE CONFIDENCE COMPARISON DISAGREED ACROSS HOSTS. `(< conf floor)` on a
     non-numeric confidence is a `ClassCastException` under Clojure and `false`
     under ClojureScript, where it compiles to a JavaScript string-versus-number
     test. Same `.cljc` file, same input, opposite outcomes:

         clj:  {:confidence \"high\"} => THREW ClassCastException
         cljs: {:confidence \"high\"} => admitted, no violation

     On the host this actor is portable to, an unreadable confidence bought a
     clean admission. On the host it is tested on, it took the whole run down
     without producing a verdict.

  3. THE FLOOR HAD NO CEILING. A confidence of `99.0` was admitted clean,
     because the only test was `(< conf 0.6)`:

         {:op :approve-sale ... :confidence 99.0} => {:ok? true :violations []}

     An advisor that reports a confidence outside `[0,1]` is not a confident
     advisor; it is one whose output does not mean what the field says it
     means. `usable-confidence?` tests `number?` FIRST, so the ordering
     comparison never sees a non-number on either host, and bounds the value on
     both sides.

  4. THE EMPTY-MAP CLIENT ANSWERED FOR A REQUEST WITH NO CLIENT ID. The
     governor's provenance test was `(nil? (store/client store (:client-id
     request)))`. A client registered as `{}` lands in the store under the
     `nil` key, and a request carrying no `:client-id` looks it up and finds it:

         request {} (no :client-id), store containing {}
         => violations=[:unknown-product]   ; NOT :no-client

     Provenance answered `yes` for a client that is not a client. The run did
     refuse — for an unrelated reason — which is the shape this workspace has
     repeatedly caught: a check that has stopped discriminating while still
     producing red. `identified?` is the missing question: asking whether an id
     is usable BEFORE asking what it resolves to.

  One more thing measured and repaired here without being a defect of the same
  kind: `:claimed-indications` given as a bare string rather than a collection
  was refused, but for nonsense. `(set \"coronary-stenosis\")` is a set of
  CHARACTERS, so the subset test compared characters against indication strings
  and reported every letter as an off-label claim. The refusal was right by
  accident and its explanation was garbage; `well-formed-indications?` asks the
  shape question directly."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [medsales.operation :as op]
            [medsales.store :as store]))

(def confidence-floor
  "Below this, a proposal escalates to a human. It is a floor on a value that
  has already been established to be usable — `usable-confidence?` runs first,
  and an unusable confidence is a hard block rather than a low one, because
  escalating it would ask a human to sign off on a number that does not mean
  anything."
  0.6)

(defn identified?
  "True for a usable identifier: a non-blank string. Used for client, product
  and buyer ids. A `nil` id is what let the empty-map client land in the store
  under the `nil` key and then answer a request that named no client at all; a
  blank string is the same hole with a different spelling."
  [id]
  (and (string? id) (not (str/blank? id))))

(defn usable-confidence?
  "True when `c` is a number in `[0,1]`.

  `number?` is tested FIRST and the ordering comparisons are only reached for
  numbers, so Clojure and ClojureScript cannot disagree: the pre-change
  `(< c 0.6)` threw on one host and returned false on the other for the same
  non-numeric input. Both bounds are checked, because a floor alone let `99.0`
  buy its way out of escalation."
  [c]
  (and (number? c) (<= 0 c) (<= c 1)))

(defn low-confidence?
  "True when a USABLE confidence is below the floor. Callers must establish
  usability first; this predicate deliberately answers false for an unusable
  value rather than guessing, so that an unusable confidence is never routed as
  a merely-low one."
  [c]
  (and (usable-confidence? c) (< c confidence-floor)))

(defn well-formed-indications?
  "True when `xs` is a collection of non-blank strings, or is absent.

  Absent is well-formed on purpose: not every sale states an indication, and a
  proposal claiming nothing claims nothing off-label. A bare STRING is not
  well-formed even though it looks like one indication — `(set \"abc\")` is a
  set of characters, and the pre-change subset test silently compared
  characters against indication strings."
  [xs]
  (or (nil? xs)
      (and (coll? xs) (not (string? xs)) (every? identified? xs))))

(defn registered-client
  "The client record for a request, or nil. Returns nil for an unusable client
  id WITHOUT consulting the store, which is the half the pre-change check was
  missing."
  [store request]
  (when (identified? (:client-id request))
    (store/client store (:client-id request))))

(defn provenance-violations
  "Violations about whether this request names a client that exists.

  Ordered so the more specific fact is reported: a request with no usable
  client id is `:no-client-id`, not `:no-client` — the difference is whether
  the caller failed to name a client or named one that is not registered, and a
  reviewer needs to be told which."
  [store request]
  (cond
    (not (identified? (:client-id request)))
    [{:rule :no-client-id
      :detail (str "request が client を名指していない（:client-id="
                   (pr-str (:client-id request)) "）")}]

    (nil? (store/client store (:client-id request)))
    [{:rule :no-client
      :detail (str "未登録 client: " (pr-str (:client-id request)))}]

    :else []))

(defn vocabulary-violations
  "Violations about the operation itself.

  An undeclared op and a reserved op are different failures and are reported as
  different rules. Undeclared means the vocabulary does not contain the word;
  reserved means it does, and the word names authority this actor does not
  hold. Both are hard — but a reviewer reading `:reserved-op` learns that
  someone asked the actor to grant a buyer licence, and a reviewer reading
  `:undeclared-op` learns that someone asked it for something nobody defined."
  [proposal]
  (let [o (:op proposal)]
    (cond
      (op/reserved? o)
      ;; `:op` is carried so a caller can pin WHICH authority boundary was
      ;; crossed, not merely that one was.
      [{:rule :reserved-op
        :op o
        :detail (str (op/label o) ": " (op/reserved-reason o))}]

      (not (op/supported? o))
      [{:rule :undeclared-op
        :op o
        :detail (str "宣言されていない op: " (op/label o)
                     "（許可されているのは "
                     (str/join ", " (sort (map name (keys op/supported))))
                     "）")}]

      :else [])))

(defn actuation-violations
  "The advisor proposes; it never writes. `:effect` must be `:propose`."
  [proposal]
  (if (= :propose (:effect proposal))
    []
    [{:rule :no-actuation
      :detail (str "effect は :propose のみ許可（直接書込禁止）。受け取った値: "
                   (pr-str (:effect proposal)))}]))

(defn confidence-violations
  "An unusable confidence is a HARD block, not an escalation. Escalating it
  would put a number in front of a human that does not mean anything, and ask
  them to sign off on it."
  [proposal]
  (if (usable-confidence? (:confidence proposal))
    []
    [{:rule :unusable-confidence
      :detail (str ":confidence は 0..1 の数でなければならない。受け取った値: "
                   (pr-str (:confidence proposal)))}]))

(defn product-violations
  "Violations about the product a citing operation names. Consulted for EVERY
  op whose `:cites-product?` is true — that is the repair for defect 1, where
  these checks reached `:approve-sale` only.

    :sale-without-product  — a citing op that named no product
    :unknown-product       — a product id nothing registered
    :product-wrong-client  — a product registered to a DIFFERENT client"
  [store request proposal]
  (let [pid (:product-id proposal)
        p   (when (identified? pid) (store/product store pid))]
    (cond
      (not (identified? pid))
      [{:rule :sale-without-product
        :detail (str (op/label (:op proposal)) " は登録済み product を名指す必要がある（:product-id="
                     (pr-str pid) "）")}]

      (nil? p)
      [{:rule :unknown-product
        :detail (str "未登録 product への販売承認は不可: " (pr-str pid))}]

      (not= (:client-id p) (:client-id request))
      [{:rule :product-wrong-client
        :detail (str "product " (pr-str pid) " は client " (pr-str (:client-id p))
                     " のもので、request の client " (pr-str (:client-id request)) " のものではない")}]

      :else [])))

(defn indication-violations
  "The subset invariant: every claimed indication must be a member of the
  product's REGISTERED `:approved-indications`. Off-label marketing is a subset
  violation, not a sales technique.

  Shape is checked before membership. A bare string is refused as
  `:malformed-indications` rather than being exploded into characters and
  reported as a long list of off-label claims — the pre-change behaviour, which
  refused for the right verdict and the wrong reason.

  `product` is passed in already resolved; callers run `product-violations`
  first and skip this when the product itself is the problem, so an
  unregistered product is not also reported as claiming everything off-label."
  [product proposal]
  (let [claimed (:claimed-indications proposal)]
    (cond
      (not (well-formed-indications? claimed))
      [{:rule :malformed-indications
        :detail (str ":claimed-indications は文字列の集合でなければならない（文字列単体は"
                     "文字の集合に展開され、部分集合検査が無意味になる）。受け取った値: "
                     (pr-str claimed))}]

      :else
      (let [off (set/difference (set claimed) (set (:approved-indications product)))]
        (if (seq off)
          [{:rule :off-label-claim
            :detail (str "適応外主張 " (vec (sort off))
                         "（オフラベル宣伝は部分集合違反であって販売技術ではない）。"
                         "登録済み承認適応: " (vec (sort (:approved-indications product))))}]
          [])))))

(defn buyer-violations
  "The conditional-membership invariant: if the product is registered
  `:restricted?`, the buyer must be a member of the client's registered
  licensed-buyers set. An unrestricted product has no such gate, which is why
  this is conditional membership rather than plain membership.

  A restricted product with no buyer named at all is `:buyer-not-named` rather
  than `:unlicensed-buyer`: `nil` is not a member of the set, so the
  pre-change check would have refused it, but it would have told the reviewer
  that a buyer failed a licence check that was never run against anybody."
  [store request product proposal]
  (if-not (:restricted? product)
    []
    (let [buyer  (:buyer-id proposal)
          buyers (store/licensed-buyers store (:client-id request))]
      (cond
        (not (identified? buyer))
        [{:rule :buyer-not-named
          :detail (str "制限品目 " (pr-str (:product-id product))
                       " の販売承認が買い手を名指していない（:buyer-id=" (pr-str buyer) "）")}]

        (not (contains? (set buyers) buyer))
        [{:rule :unlicensed-buyer
          :detail (str "制限品目の買い手 " (pr-str buyer) " が client "
                       (pr-str (:client-id request)) " の登録済み免許保有者でない")}]

        :else []))))

(defn bulk-order?
  "True for the operation the README names as elevated diversion risk. Not a
  violation — an escalation trigger, and now one that arrives at the human
  WITH the product, indication and buyer checks already computed."
  [proposal]
  (= :approve-bulk-order (:op proposal)))
