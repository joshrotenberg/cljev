(ns cljev.example.triage
  "Triage a support message: one request, three question types.

      bb -m cljev.example.triage
      bb -m cljev.example.triage \"My invoice is wrong and I need it fixed today.\""
  (:require [cljev.core :as jev]
            [clojure.string :as str]))

(def default-message
  "I was charged twice for my subscription this month. Please refund the extra charge.")

(def urgency-levels
  ["Can wait"
   "Needs attention this week"
   "Needs attention today"])

(def questions
  {:complaint (jev/noul "Is the customer complaining about something?")
   :team      (jev/choice "Which team should handle this message?"
                          [[:billing   "Charges, refunds, invoices, and payment methods"]
                           [:technical "Bugs, outages, and how to use the product"]
                           [:sales     "Pricing, plans, and new purchases"]
                           [:other     "Anything that fits none of the other teams"]])
   :urgency   (jev/score "How urgent is this message?" urgency-levels)})

(defn- pct [p]
  (format "%.0f%%" (* 100.0 p)))

(defn print-triage [client message]
  (let [{:keys [model answers usage]} (jev/ask client message questions)
        {:keys [complaint team urgency]} answers]
    (println message)
    (println)
    (println "complaint " (pct (:noul complaint)))
    (println "team      " (name (:choice team))
             (str "(confidence " (pct (:confidence team)) ")"))
    (doseq [[option p] (sort-by val > (:probabilities team))]
      (println "          " (format "%-10s %s" (name option) (pct p))))
    (println "urgency   " (format "%.2f" (double (:score urgency)))
             (str "(confidence " (pct (:confidence urgency)) ")"))
    (doseq [[level p] (sort-by key (:probabilities urgency))]
      (println "          " (format "%d %-26s %s" level (get urgency-levels level) (pct p))))
    (println)
    (println model "used" (:input-tokens usage) "input tokens")))

(defn -main [& args]
  (print-triage (jev/client)
                (if (seq args) (str/join " " args) default-message)))
