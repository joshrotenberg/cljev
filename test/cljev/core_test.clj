(ns cljev.core-test
  (:require [cheshire.core :as json]
            [cljev.core :as jev]
            [clojure.test :refer [deftest is testing]]))

(defn- reply
  ([status body] (reply status body {}))
  ([status body headers]
   {:status status
    :headers headers
    :body (if (string? body) body (json/generate-string body))}))

(defn- stub
  "A client whose requests are answered in order by `replies`. A reply that is
  a Throwable is thrown. Returns [client requests sleeps]; the last two are
  atoms recording what was sent and how long the client waited."
  ([replies] (stub replies {}))
  ([replies opts]
   (let [requests (atom [])
         sleeps (atom [])
         queue (atom replies)
         client (jev/client
                 (merge {:api-key "test-key"
                         :request-fn (fn [req]
                                       (swap! requests conj req)
                                       (let [r (first @queue)]
                                         (swap! queue rest)
                                         (if (instance? Throwable r) (throw r) r)))
                         :sleep-fn #(swap! sleeps conj %)}
                        opts))]
     [client requests sleeps])))

(defn- sent-body [req]
  (json/parse-string (:body req)))

(def ^:private usage {"input_tokens" 120 "output_tokens" 12})

(deftest question-constructors
  (is (= {:type :noul :instructions "Billing?"} (jev/noul "Billing?")))
  (is (= {:type :noul :instructions "Billing?" :criteria {:true "yes" :false "no"}}
         (jev/noul "Billing?" {:true "yes" :false "no"})))
  (is (= {:type :choice :instructions "Tone?" :criteria {:angry nil :calm nil}}
         (jev/choice "Tone?" [:angry :calm])))
  (is (= {:type :choice :instructions "Tone?" :criteria {"angry" "Hostile"}}
         (jev/choice "Tone?" {"angry" "Hostile"})))
  (is (= {:type :score :instructions "Urgency?" :criteria ["low" "high"]}
         (jev/score "Urgency?" '("low" "high"))))
  (is (= {:type :box :instructions "the cat"} (jev/box "the cat")))
  (testing "limits"
    (is (thrown? clojure.lang.ExceptionInfo (jev/choice "?" [])))
    (is (thrown? clojure.lang.ExceptionInfo (jev/choice "?" (range 256))))
    (is (some? (jev/choice "?" (range 255))))
    (is (thrown? clojure.lang.ExceptionInfo (jev/score "?" [])))
    (is (thrown? clojure.lang.ExceptionInfo (jev/score "?" (range 11))))))

(deftest client-config
  (testing "the api key is not in the printed client"
    (let [c (jev/client {:api-key "secret-key" :request-fn identity})]
      (is (not (re-find #"secret-key" (pr-str c))))))
  (testing "trailing slashes on the base url are dropped"
    (is (= "http://localhost:1"
           (:base-url (jev/client {:api-key "k" :base-url "http://localhost:1//"
                                   :request-fn identity})))))
  (testing "a blank api key is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No API key"
                          (jev/client {:api-key " "})))))

(deftest ask-sends-the-documented-request
  (let [[client requests] (stub [(reply 200 {"model" "jev-1.14.0"
                                             "answers" {"billing" {"type" "noul" "noul" 0.98}}
                                             "usage" usage})]
                                {:base-url "https://example.test"
                                 :headers {"x-team" "support"}})
        result (jev/ask client
                        {:message "I was charged twice." :customer/tier :gold}
                        {:billing (jev/noul "Is this about billing?"
                                            {:true "Charges" :false "Anything else"})})
        req (first @requests)]
    (is (= :post (:method req)))
    (is (= "https://example.test/v1/systemone" (:uri req)))
    (is (= "Bearer test-key" (get-in req [:headers "authorization"])))
    (is (= "application/json" (get-in req [:headers "content-type"])))
    (is (= "support" (get-in req [:headers "x-team"])))
    (is (= 10000 (:timeout req)))
    (is (false? (:throw req)))
    (is (not (contains? (:headers req) "typesafe-danger-zone-images")))
    (is (= {"model" "jev-latest"
            "state" {"message" "I was charged twice." "customer/tier" "gold"}
            "questions" {"billing" {"type" "noul"
                                    "instructions" "Is this about billing?"
                                    "criteria" {"true" "Charges"
                                                "false" "Anything else"}}}}
           (sent-body req)))
    (is (= {:model "jev-1.14.0"
            :answers {:billing {:type :noul :noul 0.98}}
            :usage {:input-tokens 120 :output-tokens 12}}
           result))))

(deftest ask-options
  (let [[client requests] (stub [(reply 200 {"model" "jev-1.13.0"
                                             "answers" {"where" {"type" "box"
                                                                 "box" {"top" 0.2 "bottom" 0.66
                                                                        "left" 0.19 "right" 0.68}
                                                                 "confidence" nil}}
                                             "usage" usage})])
        asset {:type "image" :format "png" :data_base64 "aGk="}
        result (jev/ask client "see img-1" {:where (jev/box "the red rectangle")}
                        {:model "jev-1.13.0"
                         :assets {"img-1" asset}
                         :images? true
                         :long-context? true
                         :timeout-ms 500
                         :headers {"x-trace" "abc"}})
        req (first @requests)]
    (is (= "true" (get-in req [:headers "typesafe-danger-zone-images"])))
    (is (= "true" (get-in req [:headers "typesafe-danger-zone-long-context"])))
    (is (= "abc" (get-in req [:headers "x-trace"])))
    (is (= 500 (:timeout req)))
    (is (= "jev-1.13.0" (get (sent-body req) "model")))
    (is (= {"img-1" {"type" "image" "format" "png" "data_base64" "aGk="}}
           (get (sent-body req) "assets")))
    (is (= {:type :box
            :box {:top 0.2 :bottom 0.66 :left 0.19 :right 0.68}
            :confidence nil}
           (get-in result [:answers :where])))))

(deftest answers-use-the-callers-keys
  (let [[client] (stub [(reply 200 {"model" "jev-1.14.0"
                                    "answers"
                                    {"tone" {"type" "choice" "choice" "calm" "confidence" 0.7
                                             "probabilities" {"angry" 0.15 "calm" 0.8 "excited" 0.05}}
                                     "route/team" {"type" "choice" "choice" "Billing team"
                                                   "confidence" 0.9
                                                   "probabilities" {"Billing team" 0.95 "sales/emea" 0.05}}
                                     "urgency" {"type" "score" "score" 1.7 "confidence" 0.55
                                                "legend" {"0" "Can wait" "1" "This week" "2" "Today"}
                                                "probabilities" {"0" 0.1 "1" 0.1 "2" 0.8}}}
                                    "usage" usage})])
        result (jev/ask client "state"
                        {:tone (jev/choice "Tone?" [:angry :calm :excited])
                         :route/team (jev/choice "Team?" {"Billing team" "Charges"
                                                          :sales/emea "New business"})
                         "urgency" (jev/score "Urgency?" ["Can wait" "This week" "Today"])})]
    (is (= {:type :choice :choice :calm :confidence 0.7
            :probabilities {:angry 0.15 :calm 0.8 :excited 0.05}}
           (get-in result [:answers :tone])))
    (is (= {:type :choice :choice "Billing team" :confidence 0.9
            :probabilities {"Billing team" 0.95 :sales/emea 0.05}}
           (get-in result [:answers :route/team])))
    (is (= {:type :score :score 1.7 :confidence 0.55
            :legend {0 "Can wait" 1 "This week" 2 "Today"}
            :probabilities {0 0.1 1 0.1 2 0.8}}
           (get-in result [:answers "urgency"])))))

(deftest raw-question-maps-and-unknown-answers
  (let [[client] (stub [(reply 200 {"model" "m"
                                    "answers" {"a" {"type" "choice" "choice" "x" "confidence" 1.0
                                                    "probabilities" {"x" 1.0}}
                                               "b" {"type" "future_kind" "some_field" 1}}
                                    "usage" usage})])
        result (jev/ask client "s" {"a" {"type" "choice" "criteria" {"x" nil}}
                                    "b" {"type" "future_kind"}})]
    (is (= "x" (get-in result [:answers "a" :choice])))
    (is (= {:type :future_kind :some-field 1} (get-in result [:answers "b"])))))

(deftest ask-requires-a-question
  (let [[client requests] (stub [])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least one question"
                          (jev/ask client "s" {})))
    (is (empty? @requests))))

(deftest ask-one-returns-the-answer
  (let [[client requests] (stub [(reply 200 {"model" "jev-1.14.0"
                                             "answers" {"q" {"type" "noul" "noul" 0.25}}
                                             "usage" usage})])
        answer (jev/ask-one client "s" (jev/noul "Is it?") {:model "jev-1.14.0"})]
    (is (= {:type :noul :noul 0.25} answer))
    (is (= {:model "jev-1.14.0" :usage {:input-tokens 120 :output-tokens 12}}
           (meta answer)))
    (is (= ["q"] (keys (get (sent-body (first @requests)) "questions"))))))

(deftest models-lists-models
  (let [[client requests] (stub [(reply 200 {"models" [{"name" "jev-latest"
                                                        "description" "Latest"
                                                        "release_date" "2026-10-14"}]})])]
    (is (= [{:name "jev-latest" :description "Latest" :release-date "2026-10-14"}]
           (jev/models client)))
    (let [req (first @requests)]
      (is (= :get (:method req)))
      (is (= "https://api.typesafe.ai/v1/models" (:uri req)))
      (is (not (contains? req :body)))
      (is (not (contains? (:headers req) "content-type"))))))

(deftest api-errors
  (testing "422 validation errors"
    (let [[client requests sleeps]
          (stub [(reply 422 {"detail" [{"loc" ["body" "questions"]
                                        "msg" "Field required"
                                        "type" "missing"}]})])
          e (try (jev/ask client "s" {:q (jev/noul "?")})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= "TypeSafe API error 422: body.questions: Field required" (ex-message e)))
      (is (= ::jev/api-error (:type (ex-data e))))
      (is (= 422 (:status (ex-data e))))
      (is (= "missing" (get-in (ex-data e) [:detail 0 "type"])))
      (is (= 1 (count @requests)))
      (is (empty? @sleeps))))
  (testing "400 with a string detail"
    (let [[client] (stub [(reply 400 {"detail" "Invalid image request."})])
          e (try (jev/models client) (catch clojure.lang.ExceptionInfo e e))]
      (is (= "TypeSafe API error 400: Invalid image request." (ex-message e)))))
  (testing "a body that is not JSON"
    (let [[client] (stub [(reply 401 "<html>nope</html>")])
          e (try (jev/models client) (catch clojure.lang.ExceptionInfo e e))]
      (is (= 401 (:status (ex-data e))))
      (is (= "<html>nope</html>" (:body (ex-data e)))))))

(deftest retries
  (let [ok (reply 200 {"models" []})]
    (testing "retry-after and retry-after-ms set the wait"
      (let [[client requests sleeps]
            (stub [(reply 429 {"detail" "slow down"} {"retry-after" "2"})
                   (reply 529 {"detail" "busy"} {"retry-after-ms" "150"})
                   ok])]
        (is (= [] (jev/models client)))
        (is (= 3 (count @requests)))
        (is (= [2000 150] @sleeps))))
    (testing "backoff doubles from the initial delay, less jitter"
      (let [[client _ sleeps] (stub [(reply 500 "") (reply 503 "") ok])]
        (jev/models client)
        (is (<= 375 (first @sleeps) 500))
        (is (<= 750 (second @sleeps) 1000))))
    (testing "backoff is capped"
      (let [[client _ sleeps] (stub [(reply 500 "") (reply 500 "") (reply 500 "") ok]
                                    {:retry {:max-retries 3 :backoff-initial-ms 400
                                             :backoff-max-ms 1000 :backoff-jitter 0}})]
        (jev/models client)
        (is (= [400 800 1000] @sleeps))))
    (testing "the last reply is thrown once retries run out"
      (let [[client requests] (stub (repeat 5 (reply 429 {"detail" "slow down"})))
            e (try (jev/models client) (catch clojure.lang.ExceptionInfo e e))]
        (is (= 429 (:status (ex-data e))))
        (is (= 3 (count @requests)))))
    (testing "max-retries 0 turns retries off"
      (let [[client requests] (stub [(reply 429 "") ok] {:retry {:max-retries 0}})]
        (is (thrown? clojure.lang.ExceptionInfo (jev/models client)))
        (is (= 1 (count @requests)))))
    (testing "connection failures are retried"
      (let [[client requests] (stub [(java.io.IOException. "reset") ok])]
        (is (= [] (jev/models client)))
        (is (= 2 (count @requests)))))
    (testing "a connection failure that persists is thrown with its cause"
      (let [cause (java.io.IOException. "reset")
            [client requests] (stub (repeat 5 cause))
            e (try (jev/models client) (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::jev/connection-error (:type (ex-data e))))
        (is (= 3 (:attempts (ex-data e))))
        (is (identical? cause (ex-cause e)))
        (is (= 3 (count @requests)))))))

(deftest image-assets
  (let [bytes (.getBytes "hi")]
    (is (= {:type "image" :format "png" :data_base64 "aGk="} (jev/image bytes :png)))
    (is (thrown? clojure.lang.ExceptionInfo (jev/image bytes)))
    (is (thrown? clojure.lang.ExceptionInfo (jev/image bytes "webp")))
    (let [f (java.io.File/createTempFile "cljev" ".JPG")]
      (try
        (spit f "hi")
        (is (= {:type "image" :format "jpg" :data_base64 "aGk="} (jev/image f)))
        (is (= "jpg" (:format (jev/image (.getPath f)))))
        (finally (.delete f))))))
