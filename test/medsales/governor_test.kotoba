(ns medsales.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [medsales.store :as store]
            [medsales.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-product! st {:product-id "P-1" :client-id "client-1"
                                 :name "surgical-implant-x"
                                 :approved-indications #{"joint-replacement" "trauma-repair"}
                                 :restricted? true})
    (store/register-licensed-buyer! st "client-1" "B-LICENSED")
    st))

(defn- sell [indications buyer]
  {:op :approve-sale :effect :propose :product-id "P-1"
   :claimed-indications indications :buyer-id buyer :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-on-label-and-licensed-buyer
  (let [st (fresh-store)
        v (governor/check req {} (sell #{"joint-replacement"} "B-LICENSED") st)]
    (is (:ok? v))))

(deftest hard-on-off-label-claim
  (testing "off-label marketing is a subset violation, not a sales technique"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (sell #{"cosmetic-enhancement"} "B-LICENSED")
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :off-label-claim (:rule %)) (:violations v))))))

(deftest hard-on-unlicensed-buyer-for-restricted-product
  (let [st (fresh-store)
        v (governor/check req {} (assoc (sell #{"joint-replacement"} "B-UNLICENSED")
                                        :confidence 0.99) st)]
    (is (:hard? v))
    (is (some #(= :unlicensed-buyer (:rule %)) (:violations v)))))

(deftest ok-unlicensed-buyer-for-unrestricted-product
  (testing "the licensed-buyer gate only applies to restricted products"
    (let [st (fresh-store)]
      (store/register-product! st {:product-id "P-2" :client-id "client-1"
                                   :name "diagnostic-kit"
                                   :approved-indications #{"screening"}
                                   :restricted? false})
      (let [v (governor/check req {} (assoc (sell #{"screening"} "B-ANY")
                                            :product-id "P-2") st)]
        (is (:ok? v))))))

(deftest hard-on-unknown-product
  (let [st (fresh-store)
        v (governor/check req {} (assoc (sell #{"joint-replacement"} "B-LICENSED")
                                        :product-id "P-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-product (:rule %)) (:violations v)))))

(deftest hard-on-foreign-product
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (sell #{"joint-replacement"} "B-LICENSED") st)]
      (is (:hard? v))
      (is (some #(= :product-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (sell #{"joint-replacement"} "B-LICENSED") st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (sell #{"joint-replacement"} "B-LICENSED")
                                        :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-bulk-order
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-bulk-order :effect :propose
                                  :product-id "P-1" :claimed-indications #{"joint-replacement"}
                                  :buyer-id "B-LICENSED" :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (sell #{"joint-replacement"} "B-LICENSED") :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
