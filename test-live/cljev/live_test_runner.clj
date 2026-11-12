(ns cljev.live-test-runner
  "Runs the tests that call the TypeSafe API (bb test:live, clojure -M:live).

  Only the exact environment value CLJEV_LIVE_TESTS=1 enables them. Without
  it the runner skips without loading the tests or touching the network, so
  an exported API key alone never starts billed requests."
  (:require [clojure.test :as test]))

(defn -main [& _]
  (cond
    (not= "1" (System/getenv "CLJEV_LIVE_TESTS"))
    (println "live tests skipped: set CLJEV_LIVE_TESTS=1 to run them (billed)")

    (nil? (System/getenv "TYPESAFE_API_KEY"))
    (do (println "live tests need TYPESAFE_API_KEY")
        (System/exit 2))

    :else
    (do (require 'cljev.live-test)
        (let [{:keys [fail error]} (test/run-tests 'cljev.live-test)]
          (shutdown-agents)
          (System/exit (if (zero? (+ fail error)) 0 1))))))
