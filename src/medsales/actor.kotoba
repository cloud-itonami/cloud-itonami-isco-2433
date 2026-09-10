(ns medsales.actor
  "TechnicalMedicalSalesActor — the ISCO-08 2433 community medsales actor as a
  `langgraph.graph/state-graph` (ADR-2607011000 / CLAUDE.md Actors
  section). One graph run = one medsales operation request
  (intake → advise → govern → decide → commit/hold, with a
  human-approval interrupt for escalated proposals). No infinite
  internal loop; checkpointed per superstep so an interrupted run can
  resume after human sign-off. Modeled on cloud-itonami-isco-2411's
  accounting.actor.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                             +-> :request-approval   (:escalate? true, interrupt-before)
                                             +-> :hold               (:hard? true)
  ```

  The unconditional invariant: the TechnicalMedicalSalesAdvisor can never
  directly commit a record the TechnicalMedicalSalesGovernor refuses —
  every commit-record! call is gated behind `:decide`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [medsales.advisor :as advisor]
            [medsales.governor :as governor]
            [medsales.ledger :as led]
            [medsales.phase :as phase]
            [medsales.store :as store]))

(defn- append-chained!
  "Append `m` to the store's ledger as a chained entry. The chain is built here
  because this is where the previous hash is known; `store/append-ledger!`
  appends what it is given."
  [st m]
  (store/append-ledger! st (led/entry (store/ledger st) m)))

(defn build-graph
  "Build a compiled TechnicalMedicalSalesActor graph. `store` implements
  `medsales.store/Store`. `advisor` implements
  `medsales.advisor/Advisor` (defaults to `mock-advisor`).
  `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                   (fn [{:keys [verdict]}]
                     {:disposition (phase/of-verdict verdict)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                   (fn [{:keys [request proposal disposition]}]
                     (let [record {:client-id (:client-id request)
                                   :op (:op proposal)
                                   :product-id (:product-id proposal)
                                   :buyer-id (:buyer-id proposal)
                                   :payload proposal}
                           ;; The commit node is reached either directly (the
                           ;; governor admitted it) or from the interrupted
                           ;; :request-approval node (a human resumed the
                           ;; thread). `:disposition` still carries which,
                           ;; because :request-approval does not overwrite it.
                           approved-by (if (phase/approved-commit? disposition) :human :actor)]
                       (store/commit-record! store record)
                       (append-chained! store (led/commit-entry record approved-by))
                       {:record record
                        :audit [{:node :commit :record record :approved-by approved-by}]})))
      (g/add-node :hold
                   (fn [{:keys [verdict]}]
                     (append-chained! store (led/hold-entry verdict))
                     {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one operation request to completion or interrupt. `thread-id`
  scopes checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of
  resuming the thread)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
