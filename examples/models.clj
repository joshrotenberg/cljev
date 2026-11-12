#!/usr/bin/env bb
;; List the models and aliases the account can use. A quick check that the
;; API key works.
;;
;;   bb examples/models.clj

(ns examples.models
  (:require [cljev.core :as jev]))

(doseq [{:keys [name release-date description]} (jev/models (jev/client))]
  (println (format "%-14s %-12s %s" name release-date description)))
