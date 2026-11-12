(ns cljev.test-runner
  "Runs every test namespace and exits with a failing status when any test
  fails, under Babashka (bb test) and on the JVM (clojure -M:test)."
  (:require [cljev.core-test]
            [cljev.example-test]
            [cljev.http-test]
            [clojure.test :as test]))

(def namespaces
  '[cljev.core-test
    cljev.http-test
    cljev.example-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply test/run-tests namespaces)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
