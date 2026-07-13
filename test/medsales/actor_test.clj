(ns medsales.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [medsales.actor :as actor]
            [medsales.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-product! st {:product-id "P-1" :client-id "client-1"
                                 :name "surgical-implant-x"
                                 :approved-indications #{"joint-replacement"}
                                 :restricted? true})
    (store/register-licensed-buyer! st "client-1" "B-LICENSED")
    st))

(deftest commits-an-on-label-licensed-sale
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-sale :stake :low
                 :product-id "P-1" :claimed-indications #{"joint-replacement"}
                 :buyer-id "B-LICENSED"}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-off-label-sale
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-sale :stake :low
                 :product-id "P-1" :claimed-indications #{"cosmetic-use"}
                 :buyer-id "B-LICENSED"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-approves-bulk-order-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-bulk-order :stake :high
                 :product-id "P-1" :claimed-indications #{"joint-replacement"}
                 :buyer-id "B-LICENSED"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
