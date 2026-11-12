(ns cljev.example-test
  "The examples, run against a stubbed request function."
  (:require [cheshire.core :as json]
            [cljev.core :as jev]
            [cljev.example.classify :as classify]
            [cljev.example.grep :as grep]
            [cljev.example.models :as models]
            [cljev.example.search :as search]
            [cljev.example.triage :as triage]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

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

(deftest search-windows
  (let [text (str/join "\n" (map #(str "line " %) (range 1 76)))
        ws (search/windows "f.clj" text {:size 30 :overlap 10})]
    (testing "windows overlap, and line numbers are 1-based and inclusive"
      (is (= [[1 30] [21 50] [41 70] [61 75]] (map (juxt :start :end) ws)))
      (is (str/starts-with? (:text (second ws)) "line 21\n"))
      (is (str/ends-with? (:text (second ws)) "line 50")))
    (testing "a last window that only repeats the overlap is dropped"
      (is (= [[1 30] [21 50]]
             (map (juxt :start :end)
                  (search/windows "f.clj" (str/join "\n" (repeat 50 "x")) {:size 30 :overlap 10})))))
    (testing "a short file is one window, and an empty file none"
      (is (= [[1 3]] (map (juxt :start :end) (search/windows "f.clj" "a\nb\nc" {}))))
      (is (= [] (search/windows "f.clj" "" {}))))))

(deftest search-keyword-ranking
  (let [chunks [{:text "the session is closed when idle"}
                {:text "idle idle idle sessions are swept, each idle session closed"}
                {:text "nothing relevant here"}
                {:text "a session"}]
        ranked (search/keyword-ranked "How are idle sessions closed?" chunks)]
    (is (= ["idle" "sessions" "closed"] (search/terms "How are idle sessions closed?")))
    (is (= (chunks 1) (first (first ranked))) "more of the query's words ranks first")
    (is (= [0.0 0.0] (map second (drop 2 ranked)))
        "no shared word scores zero; words are matched exactly, so session is not sessions")))

(deftest search-batches
  (let [chunk (fn [n] {:text (apply str (repeat n "x"))})]
    (is (= [[3 3] [3] [9] [2]]
           (map #(map (comp count :text) %)
                (search/batches (map chunk [3 3 3 9 2]) 6)))
        "a batch closes when the next window would pass the limit; an oversized window gets its own")
    (is (= [] (search/batches [] 6)))))

(defn- needle-request-fn
  "Answers yes for the passages whose text mentions a needle. Fails the
  request whose state mentions a failure."
  [calls]
  (fn [{:keys [body]}]
    (swap! calls inc)
    (let [{:strs [state questions]} (json/parse-string body)]
      (if (some #(str/includes? % "failure") (vals state))
        {:status 400 :headers {} :body (json/generate-string {"detail" "Bad batch"})}
        {:status 200
         :headers {}
         :body (json/generate-string
                {"model" "jev-1.14.0"
                 "answers" (into {}
                                 (map (fn [id]
                                        [id {"type" "noul"
                                             "noul" (if (str/includes? (get state id) "needle")
                                                      0.9 0.1)}]))
                                 (keys questions))
                 "usage" {"input_tokens" 50 "output_tokens" 1}})}))))

(deftest search-ranks-passages
  (let [calls (atom 0)
        client (jev/client {:api-key "k" :compression nil :retry {:max-retries 0}
                            :request-fn (needle-request-fn calls)})
        chunks (mapv (fn [i] {:file "f.clj" :start i :end i
                              :text (cond (= i 7) "the needle is here"
                                          (= i 3) "session timeout"
                                          :else (str "hay " i))})
                     (range 10))]
    (testing "every window is asked about, in batches, and the best comes first"
      (let [{:keys [hits stats]} (search/search client "find it" chunks {:max-chars 30})]
        (is (= 7 (:start (first (first hits)))))
        (is (= 0.9 (second (first hits))))
        (is (= 10 (count hits)))
        (is (= 10 (:windows stats)))
        (is (= (:requests stats) @calls))
        (is (< 1 (:requests stats)))
        (is (= (* 50 (:requests stats)) (:input-tokens stats)))
        (is (= [] (:errors stats)))))
    (testing "a prefilter asks only about the windows the keywords pick"
      (let [{:keys [hits stats]} (search/search client "session timeout" chunks {:prefilter 1})]
        (is (= 1 (:windows stats)))
        (is (= [3] (map (comp :start first) hits)))))
    (testing "a failed batch is reported and left out"
      (let [chunks (conj chunks {:file "g.clj" :start 1 :end 1 :text "failure"})
            {:keys [hits stats]} (search/search client "find it" chunks {:max-chars 10})]
        (is (= 10 (count hits)))
        (is (= 1 (count (:errors stats))))
        (is (str/includes? (first (:errors stats)) "Bad batch"))))
    (testing "the printed result names the file and lines"
      (is (= ["0.90  f.clj:7-7  the needle is here"]
             (binding [*err* (java.io.StringWriter.)]
               (str/split-lines
                (with-out-str (search/print-search client "find it" chunks 1 {})))))))))
