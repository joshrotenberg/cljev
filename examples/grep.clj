#!/usr/bin/env bb
;; A grep that matches on meaning: print the lines of stdin that fit a
;; description. The lines go in the state once and each line gets its own
;; yes/no question, so a batch of lines costs one request. Prints the
;; probability, line number, and line for every line at or above THRESHOLD
;; (default 0.5).
;;
;;   bb examples/grep.clj "mentions a deadline" < notes.txt
;;   THRESHOLD=0.9 bb examples/grep.clj "is a question" < chat.log

(ns examples.grep
  (:require [cljev.core :as jev]
            [clojure.string :as str]))

(def description (str/join " " *command-line-args*))

(when (str/blank? description)
  (binding [*out* *err*]
    (println "usage: grep.clj DESCRIPTION < lines"))
  (System/exit 2))

(def threshold (parse-double (or (System/getenv "THRESHOLD") "0.5")))
(def batch-size 100)
(def client (jev/client))

(def numbered-lines
  (->> (line-seq (java.io.BufferedReader. *in*))
       (map-indexed (fn [i line] [(inc i) line]))
       (remove (comp str/blank? second))))

(defn- matches
  "Ask about one batch of [number line] pairs. Returns [number line probability]
  for each line."
  [batch]
  (let [state (into {} (map (fn [[n line]] [(str "line_" n) line])) batch)
        questions (into {}
                        (map (fn [[n _]]
                               [n (jev/noul (str "Does `line_" n "` fit this description: "
                                                 description "?"))]))
                        batch)
        answers (:answers (jev/ask client state questions))]
    (for [[n line] batch]
      [n line (:noul (get answers n))])))

(doseq [batch (partition-all batch-size numbered-lines)
        [n line p] (matches batch)
        :when (>= p threshold)]
  (println (str (format "%.2f" (double p)) "\t" n "\t" line)))
