(ns cljev.example.models
  "List the models and aliases the account can use. A quick check that the
  API key works.

      bb -m cljev.example.models"
  (:require [cljev.core :as jev]))

(defn print-models [client]
  (let [models (jev/models client)
        width (fn [k] (apply max 0 (map (comp count k) models)))
        row (str "%-" (width :name) "s  %-" (width :release-date) "s  %s")]
    (doseq [{:keys [name release-date description]} models]
      (println (format row name release-date description)))))

(defn -main [& _]
  (print-models (jev/client)))
