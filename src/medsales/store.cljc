(ns medsales.store
  "SSoT for the ISCO-08 2433 community technical & medical sales
  (excluding ICT) actor (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section). Modeled on cloud-itonami-isco-4311's
  bookkeeping.store.

  Domain:

    client         — a registered organization (:client-id, :name)
    product        — a registered product {:product-id :client-id
                     :name :approved-indications #{indication-str}
                     :restricted? bool}. `:approved-indications` is
                     the registered set a proposed sale's claimed
                     indications must be a subset of (no off-label
                     marketing); `:restricted?` marks a
                     prescription-only / controlled product whose
                     sale requires a licensed buyer.
    licensed-buyer — a registered licensed buyer id for this client
                     (a set, kept separately from products since one
                     buyer roster serves many restricted products).
    record         — a committed operating record (approved sale) —
                     written ONLY via commit-record!.
    ledger         — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (product [s product-id])
  (licensed-buyers [s client-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-product! [s p])
  (register-licensed-buyer! [s client-id buyer-id])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (product [_ product-id] (get-in @a [:products product-id]))
  (licensed-buyers [_ client-id] (get-in @a [:licensed-buyers client-id] #{}))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-product! [s p]
    (swap! a assoc-in [:products (:product-id p)] p) s)
  (register-licensed-buyer! [s client-id buyer-id]
    (swap! a update-in [:licensed-buyers client-id] (fnil conj #{}) buyer-id) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :products {} :licensed-buyers {}
                                    :records [] :ledger []}
                                   seed)))))
