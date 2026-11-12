#!/usr/bin/env bb
;; List the models and aliases the account can use. A quick check that the
;; API key works.
;;
;;   bb examples/models.clj

(ns examples.models
  (:require [cljev.core :as jev]))

(let [models (jev/models (jev/client))
      width (fn [k] (apply max 0 (map (comp count k) models)))
      row (str "%-" (width :name) "s  %-" (width :release-date) "s  %s")]
  (doseq [{:keys [name release-date description]} models]
    (println (format row name release-date description))))
