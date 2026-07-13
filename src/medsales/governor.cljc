(ns medsales.governor
  "TechnicalMedicalSalesGovernor — the independent safety/
  traceability layer for the ISCO-08 2433 community technical &
  medical sales (excluding ICT) actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. Medical-sales twist:
  a claimed indication set must be a SUBSET of the registered
  approved-indications set — off-label marketing is a subset
  violation, not a sales technique — and a restricted product may
  only be sold to a REGISTERED licensed buyer.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. product basis      — a sale approval must cite a REGISTERED
                           product belonging to this client.
    4. indication subset  — the proposed claimed-indications set must
                           be a subset of the product's registered
                           :approved-indications set (off-label
                           marketing is a subset violation, not a
                           sales technique).
    5. licensed-buyer gate — if the product is :restricted?, the
                           proposed buyer-id must be a member of the
                           client's registered licensed-buyers set (no
                           unlicensed buyer for a controlled product).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :approve-bulk-order (large-quantity order, elevated
                           diversion risk).
    7. low confidence (< `confidence-floor`)."
  (:require [clojure.set :as set]
            [medsales.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record p buyers]
  (let [{:keys [op claimed-indications buyer-id]} proposal
        approve? (= :approve-sale op)
        off-label (when p (set/difference (set claimed-indications) (:approved-indications p)))]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and approve? (nil? p))
      (conj {:rule :unknown-product :detail "未登録 product への販売承認は不可"})

      (and approve? p (not= (:client-id p) (:client-id request)))
      (conj {:rule :product-wrong-client :detail "product が別 client のもの"})

      (and approve? p (seq off-label))
      (conj {:rule :off-label-claim
             :detail (str "適応外主張 " (vec off-label)
                          "（オフラベル宣伝は部分集合違反であって販売技術ではない）")})

      (and approve? p (:restricted? p) (not (contains? buyers buyer-id)))
      (conj {:rule :unlicensed-buyer
             :detail (str "制限品目の買い手 " buyer-id " が登録済み免許保有者でない")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `medsales.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        p (some->> (:product-id proposal) (store/product store))
        buyers (store/licensed-buyers store (:client-id request))
        hard (hard-violations {:request request :proposal proposal}
                              client-record p buyers)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :approve-bulk-order (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
