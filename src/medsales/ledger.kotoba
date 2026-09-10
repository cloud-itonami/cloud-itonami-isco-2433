(ns medsales.ledger
  "Append-only audit ledger for the ISCO-08 2433 technical & medical sales
  actor: entry construction, hash chaining, and verification.

  Runtime: portable `.cljc`. The chain hash is a pure polynomial over the
  entry's printed form, chosen so that both Clojure and ClojureScript compute
  the *same* number: intermediate values stay below 2^36, well inside the
  exactly-representable integer range of a double, so no host-specific 32-bit
  wrap or BigInt is involved. `hash` from clojure.core was not used because it
  is explicitly not stable across runtimes.

  Why this namespace exists. The store held the ledger as a vector and the
  store's own docstring called it append-only, but nothing in the value made a
  dropped or reordered entry detectable: any prefix of the ledger, and any
  permutation of it, was indistinguishable from the real thing. 'Append-only'
  was a property of the code path, not of the artifact — and the code path is
  exactly what an audit is trying to check. For the record of which sales of a
  controlled product were approved, and to which licensed buyer, that is the
  difference between an audit that can show what was approved and one that can
  only show what someone chose to leave in the file.

  Separately, the ledger could not answer the question this actor's
  human-approval interrupt exists to answer. Measured on the pre-change tree,
  one automatic `:approve-sale` commit and one human-approved
  `:approve-bulk-order` commit produced two entries of the form

      {:disposition :commit :record {:client-id .. :op .. :product-id .. :payload ..}}

  differing only in the op name inside the record. Nothing in either entry said
  whether a person had signed it. `commit-entry` therefore takes the approval
  provenance as part of the entry, not as an afterthought."
  (:require [clojure.string :as str]))

(def ^:private modulus 2147483647)

(defn- code-point-at [s i]
  #?(:clj  (int (.charAt ^String s i))
     :cljs (.charCodeAt s i)))

(defn chain-hash
  "H(prev, content) as an integer in [0, modulus). Deterministic and identical
  under Clojure and ClojureScript."
  [prev content]
  (let [s (pr-str content)
        n (count s)]
    (loop [i 0
           h (mod (+ 1469598103 prev) modulus)]
      (if (>= i n)
        h
        (recur (inc i)
               (mod (+ (* h 31) (code-point-at s i)) modulus))))))

(defn entry
  "Build the next chained entry for `ledger` (a vector of entries) from the
  plain map `m`. Adds `:ledger/seq`, `:ledger/prev` and `:ledger/hash`.

  The hash covers `m` and the previous hash, so it commits to both the entry's
  content and its position."
  [ledger m]
  (let [prev (if (seq ledger) (:ledger/hash (peek (vec ledger))) 0)
        seq-n (count ledger)
        body (assoc m :ledger/seq seq-n :ledger/prev prev)]
    (assoc body :ledger/hash (chain-hash prev (dissoc body :ledger/hash)))))

(defn append
  "Pure append: ledger vector -> ledger vector. This is the only way an entry
  should enter the ledger, because it is where the chain is extended."
  [ledger m]
  (conj (vec ledger) (entry ledger m)))

(defn verify
  "Recompute the chain. Returns `{:ok? bool :length n :broken-at i :reason kw}`.

  A truncated ledger verifies as intact — a chain cannot detect that entries it
  never saw are missing. That is a real limit, not an oversight: detecting
  truncation needs an external anchor (a signed head), which this in-memory
  store does not have. `verify` therefore claims only what it can show, and
  `:reason` names which check failed rather than returning a bare false."
  [ledger]
  (let [v (vec ledger)]
    (loop [i 0 prev 0]
      (if (>= i (count v))
        {:ok? true :length (count v)}
        (let [e (nth v i)]
          (cond
            (not= (:ledger/seq e) i)
            {:ok? false :length (count v) :broken-at i :reason :seq-mismatch}

            (not= (:ledger/prev e) prev)
            {:ok? false :length (count v) :broken-at i :reason :prev-mismatch}

            (not= (:ledger/hash e)
                  (chain-hash prev (dissoc e :ledger/hash)))
            {:ok? false :length (count v) :broken-at i :reason :hash-mismatch}

            :else
            (recur (inc i) (:ledger/hash e))))))))

(defn commit-entry
  "Ledger entry for a write. `approved-by` is `:human` when the write was
  reached by a human resuming an escalated thread, `:actor` when the governor
  admitted it without escalation. Recording `:actor` explicitly rather than
  omitting the key keeps the two cases the same shape, so a reader cannot
  mistake an absent field for an unaudited one."
  [record approved-by]
  {:disposition :commit
   :phase :commit
   :approved-by approved-by
   :record record})

(defn hold-entry
  "Ledger entry for a refusal. Carries the violations so the ledger explains
  the refusal without needing the run that produced it."
  [verdict]
  {:disposition :hold
   :phase :hold
   :approved-by :none
   :verdict verdict})

(defn summary
  "One line per entry, for operators and for `medsales.sim` output."
  [ledger]
  (str/join "\n"
            (for [e ledger]
              (str (:ledger/seq e) " " (name (:disposition e))
                   " approved-by=" (name (or (:approved-by e) :unrecorded))
                   " hash=" (:ledger/hash e)))))
