(ns cljev.example.grep
  "A grep that matches on meaning: print the lines of stdin that fit a
  description. The lines go in the state once and each line gets its own
  yes/no question, so a batch of lines costs one request. Prints the
  probability, line number, and line for every line at or above THRESHOLD
  (default 0.5).

      bb -m cljev.example.grep \"mentions a deadline\" < notes.txt
      THRESHOLD=0.9 bb -m cljev.example.grep \"is a question\" < chat.log"
  (:require [cljev.core :as jev]
            [clojure.string :as str]))

(def batch-size 100)

(defn- matches
  "Ask about one batch of [number line] pairs. Returns [number line
  probability] for each line."
  [client description batch]
  (let [state (into {} (map (fn [[n line]] [(str "line_" n) line])) batch)
        questions (into {}
                        (map (fn [[n _]]
                               [n (jev/noul (str "Does `line_" n "` fit this description: "
                                                 description "?"))]))
                        batch)
        answers (:answers (jev/ask client state questions))]
    (for [[n line] batch]
      [n line (:noul (get answers n))])))

(defn print-matches
  "Print the lines of `lines` that fit `description` with a probability of
  at least `threshold`. Blank lines are skipped; line numbers count them."
  [client description lines threshold]
  (let [numbered (->> lines
                      (map-indexed (fn [i line] [(inc i) line]))
                      (remove (comp str/blank? second)))]
    (doseq [batch (partition-all batch-size numbered)
            [n line p] (matches client description batch)
            :when (>= p threshold)]
      (println (str (format "%.2f" (double p)) "\t" n "\t" line)))))

(defn -main [& args]
  (let [description (str/join " " args)]
    (when (str/blank? description)
      (binding [*out* *err*]
        (println "usage: bb -m cljev.example.grep DESCRIPTION < lines"))
      (System/exit 2))
    (print-matches (jev/client)
                   description
                   (line-seq (java.io.BufferedReader. *in*))
                   (parse-double (or (System/getenv "THRESHOLD") "0.5")))))
