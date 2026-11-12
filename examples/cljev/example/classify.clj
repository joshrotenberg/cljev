(ns cljev.example.classify
  "Classify each line of stdin into one of the labels given as arguments.
  Sends one request per line, several at a time, and prints label,
  confidence, and line, tab-separated. Lines whose confidence is below
  CONFIDENCE (default 0.5) are marked with a \"?\".

      git log --format=%s -20 | bb -m cljev.example.classify feature fix docs chore
      CONFIDENCE=0.8 bb -m cljev.example.classify spam \"not spam\" < messages.txt"
  (:require [cljev.core :as jev]
            [clojure.string :as str]))

(defn print-labels
  "Label each of `lines` and print the results. Failed lines are reported on
  stderr and do not stop the rest."
  [client labels lines threshold]
  (let [question (jev/choice "Which label best describes this text?" labels)
        ;; ask-many returns results in input order and gives a failed line an
        ;; :error instead of stopping the rest
        results (jev/ask-many client lines {:label question} {:concurrency 4})]
    (doseq [[line {:keys [answers error]}] (map vector lines results)]
      (if error
        (binding [*out* *err*]
          (println (str "failed: " line ": " (ex-message error))))
        (let [{:keys [choice confidence]} (:label answers)]
          (println (str choice (when (< confidence threshold) "?")
                        "\t" (format "%.2f" (double confidence))
                        "\t" line)))))))

(defn -main [& labels]
  (when (< (count labels) 2)
    (binding [*out* *err*]
      (println "usage: bb -m cljev.example.classify LABEL LABEL [LABEL ...] < lines"))
    (System/exit 2))
  (print-labels (jev/client)
                labels
                (vec (remove str/blank? (line-seq (java.io.BufferedReader. *in*))))
                (parse-double (or (System/getenv "CONFIDENCE") "0.5")))
  (shutdown-agents))
