#!/usr/bin/env bb
;; Classify each line of stdin into one of the labels given as arguments.
;; Sends one request per line, several at a time, and prints
;; label, confidence, and line, tab-separated. Lines whose confidence is
;; below CONFIDENCE (default 0.5) are marked with a "?".
;;
;;   git log --format=%s -20 | bb examples/classify.clj feature fix docs chore
;;   CONFIDENCE=0.8 bb examples/classify.clj spam "not spam" < messages.txt

(ns examples.classify
  (:require [cljev.core :as jev]
            [clojure.string :as str]))

(def labels *command-line-args*)

(when (< (count labels) 2)
  (binding [*out* *err*]
    (println "usage: classify.clj LABEL LABEL [LABEL ...] < lines"))
  (System/exit 2))

(def threshold (parse-double (or (System/getenv "CONFIDENCE") "0.5")))
(def client (jev/client))
(def question (jev/choice "Which label best describes this text?" labels))

(defn- classify [line]
  (try
    (jev/ask-one client line question)
    (catch clojure.lang.ExceptionInfo e
      {:error (ex-message e)})))

(def lines
  (remove str/blank? (line-seq (java.io.BufferedReader. *in*))))

;; pmap keeps the output in input order and runs a bounded number of
;; requests at a time.
(doseq [[line {:keys [choice confidence error]}] (map vector lines (pmap classify lines))]
  (if error
    (binding [*out* *err*]
      (println (str "failed: " line ": " error)))
    (println (str choice (when (< confidence threshold) "?")
                  "\t" (format "%.2f" (double confidence))
                  "\t" line))))

(shutdown-agents)
