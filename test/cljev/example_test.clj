(ns cljev.example-test
  "The examples, run against a stubbed request function."
  (:require [cheshire.core :as json]
            [cljev.core :as jev]
            [cljev.example.classify :as classify]
            [cljev.example.grep :as grep]
            [cljev.example.models :as models]
            [cljev.example.triage :as triage]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- answer
  "A canned answer: the first option of a choice, the top level of a score,
  and yes for a noul whose instructions mention an even line number."
  [{:strs [type criteria instructions]}]
  (case type
    "noul" {"type" "noul"
            "noul" (if (re-find #"line_\d*[02468]`" (str instructions)) 0.9 0.1)}
    "choice" (let [[chosen & others] (keys criteria)]
               {"type" "choice" "choice" chosen "confidence" 0.7
                "probabilities" (into {chosen 0.7}
                                      (map (fn [k] [k (/ 0.3 (count others))]))
                                      others)})
    "score" (let [n (count criteria)]
              {"type" "score" "score" (double (dec n)) "confidence" 0.6
               "legend" (into {} (map-indexed (fn [i c] [(str i) c])) criteria)
               "probabilities" (into {} (map (fn [i] [(str i) (if (= i (dec n)) 1.0 0.0)]))
                                     (range n))})))

(defn- request-fn [{:keys [uri body]}]
  {:status 200
   :headers {}
   :body (json/generate-string
          (if (str/ends-with? uri "/v1/models")
            {"models" [{"name" "jev-latest" "description" "Latest"
                        "release_date" "2026-10-14"}
                       {"name" "jev-preview" "description" "Preview"
                        "release_date" "2026-10-22T02:00:00+00:00"}]}
            {"model" "jev-1.14.0"
             "answers" (update-vals (get (json/parse-string body) "questions") answer)
             "usage" {"input_tokens" 100 "output_tokens" 3}}))})

(def ^:private client
  (jev/client {:api-key "k" :request-fn request-fn :compression nil}))

(deftest models-example
  (is (= ["jev-latest   2026-10-14                 Latest"
          "jev-preview  2026-10-22T02:00:00+00:00  Preview"]
         (str/split-lines (with-out-str (models/print-models client))))))

(deftest triage-example
  (let [out (with-out-str (triage/print-triage client "Refund me."))]
    (is (str/starts-with? out "Refund me.\n"))
    (is (str/includes? out "team       billing (confidence 70%)"))
    (is (str/includes? out "urgency    2.00 (confidence 60%)"))
    (is (str/includes? out "jev-1.14.0 used 100 input tokens"))))

(deftest classify-example
  (is (= ["fix?\t0.70\tfirst line" "fix?\t0.70\tsecond line"]
         (str/split-lines
          (with-out-str
            (classify/print-labels client ["fix" "feature"] ["first line" "second line"] 0.8)))))
  (is (= ["fix\t0.70\tfirst line"]
         (str/split-lines
          (with-out-str
            (classify/print-labels client ["fix" "feature"] ["first line"] 0.5))))))

(deftest grep-example
  (is (= ["0.90\t2\ttwo" "0.90\t4\tfour"]
         (str/split-lines
          (with-out-str
            (grep/print-matches client "anything" ["one" "two" "" "four" "five"] 0.5))))))
