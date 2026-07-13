(ns medsales.advisor
  "TechnicalMedicalSalesAdvisor — proposes a sale operation (approve a
  sale, approve a bulk order) for a registered organization. Swappable
  mock/llm; the advisor ONLY proposes — `medsales.governor` checks the
  indication subset and the licensed-buyer gate independently. Modeled
  on cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-sale|:approve-bulk-order
               :effect :propose :product-id str
               :claimed-indications #{str} :buyer-id str :stake kw
               :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake product-id claimed-indications buyer-id] :as request}]
  {:op op
   :effect :propose
   :product-id product-id
   :claimed-indications claimed-indications
   :buyer-id buyer-id
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a technical/medical sales advisor. Given a request, propose
   an :op, the :product-id, :claimed-indications and :buyer-id, an
   honest :confidence and a :stake. Never claim an off-label
   indication or sell a restricted product to an unlicensed buyer as
   conforming — the governor checks both against the registered
   product record.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
