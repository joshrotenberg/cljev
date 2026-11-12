(ns cljev.http-test
  "Round trips through the real HTTP client against a local server."
  (:require [cheshire.core :as json]
            [cljev.core :as jev]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as server]))

(defn- with-server
  "Run `(f base-url)` against a local server that answers with `handler`."
  [handler f]
  (let [stop (server/run-server handler {:port 0 :ip "127.0.0.1"})
        port (:local-port (meta stop))]
    (try
      (f (str "http://127.0.0.1:" port))
      (finally (stop)))))

(defn- json-reply [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (json/generate-string body)})

(deftest system-one-round-trip
  (let [seen (atom nil)]
    (with-server
      (fn [req]
        (reset! seen (assoc req :body (some-> (:body req) slurp json/parse-string)))
        (json-reply 200 {"model" "jev-1.14.0"
                         "answers" {"tone" {"type" "choice" "choice" "calm" "confidence" 0.7
                                            "probabilities" {"angry" 0.2 "calm" 0.8}}}
                         "usage" {"input_tokens" 9 "output_tokens" 1}}))
      (fn [base-url]
        (let [client (jev/client {:api-key "k-123" :base-url base-url})
              result (jev/ask client "All good, thanks."
                              {:tone (jev/choice "Tone?" [:angry :calm])}
                              {:long-context? true})]
          (is (= {:model "jev-1.14.0"
                  :answers {:tone {:type :choice :choice :calm :confidence 0.7
                                   :probabilities {:angry 0.2 :calm 0.8}}}
                  :usage {:input-tokens 9 :output-tokens 1}}
                 result))
          (is (= :post (:request-method @seen)))
          (is (= "/v1/systemone" (:uri @seen)))
          (is (= "Bearer k-123" (get-in @seen [:headers "authorization"])))
          (is (= "true" (get-in @seen [:headers "typesafe-danger-zone-long-context"])))
          (is (= {"model" "jev-latest"
                  "state" "All good, thanks."
                  "questions" {"tone" {"type" "choice" "instructions" "Tone?"
                                       "criteria" {"angry" nil "calm" nil}}}}
                 (:body @seen))))))))

(deftest retries-then-succeeds
  (let [calls (atom 0)]
    (with-server
      (fn [_]
        (if (= 1 (swap! calls inc))
          (assoc-in (json-reply 429 {"detail" "slow down"})
                    [:headers "retry-after-ms"] "1")
          (json-reply 200 {"models" [{"name" "jev-latest" "description" "Latest"
                                      "release_date" "2026-10-14"}]})))
      (fn [base-url]
        (is (= [{:name "jev-latest" :description "Latest" :release-date "2026-10-14"}]
               (jev/models (jev/client {:api-key "k" :base-url base-url}))))
        (is (= 2 @calls))))))

(deftest unauthorized
  (with-server
    (fn [_] (json-reply 401 {"detail" "Invalid API key"}))
    (fn [base-url]
      (let [e (try (jev/models (jev/client {:api-key "bad" :base-url base-url}))
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::jev/api-error (:type (ex-data e))))
        (is (= 401 (:status (ex-data e))))
        (is (= "Invalid API key" (:detail (ex-data e))))))))

(deftest unreachable-server
  (let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
        client (jev/client {:api-key "k"
                            :base-url (str "http://127.0.0.1:" port)
                            :retry {:max-retries 1 :backoff-initial-ms 1}})
        e (try (jev/models client) (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::jev/connection-error (:type (ex-data e))))
    (is (= 2 (:attempts (ex-data e))))))

(deftest compressed-body-round-trip
  (let [seen (atom nil)
        big (apply str (repeat 400 "lorem ipsum "))]
    (with-server
      (fn [req]
        (reset! seen {:encoding (get-in req [:headers "content-encoding"])
                      :body (with-open [in (java.util.zip.GZIPInputStream. (:body req))]
                              (json/parse-string (slurp in :encoding "UTF-8")))})
        (json-reply 200 {"model" "jev-1.14.0"
                         "answers" {"q" {"type" "noul" "noul" 0.5}}
                         "usage" {"input_tokens" 9 "output_tokens" 1}}))
      (fn [base-url]
        (jev/ask (jev/client {:api-key "k" :base-url base-url :compression :gzip})
                 big {:q (jev/noul "?")})
        (is (= "gzip" (:encoding @seen)))
        (is (= big (get-in @seen [:body "state"])))))))

(deftest multipart-round-trip
  (let [seen (atom nil)
        img (jev/image (.getBytes "not-really-a-png") :png)]
    (with-server
      (fn [req]
        (reset! seen {:content-type (get-in req [:headers "content-type"])
                      :body (slurp (:body req) :encoding "ISO-8859-1")})
        (json-reply 200 {"model" "jev-1.14.0"
                         "answers" {"q" {"type" "noul" "noul" 0.5}}
                         "usage" {"input_tokens" 9 "output_tokens" 1}}))
      (fn [base-url]
        (jev/ask (jev/client {:api-key "k" :base-url base-url})
                 {:photo img} {:q (jev/noul "?")} {:images? true})
        (let [{:keys [content-type body]} @seen
              request-at (str/index-of body "name=\"request\"")
              image-at (str/index-of body "name=\"image-0\"")]
          (is (str/starts-with? content-type "multipart/form-data; boundary="))
          (is (some? request-at))
          (is (some? image-at))
          (is (< request-at image-at) "the JSON part comes first")
          (is (str/includes? body (str "\"photo\":\"" (:ref img) "\"")))
          (is (str/includes? body "\"part\":\"image-0\""))
          (is (str/includes? body "Content-Type: image/png"))
          (is (str/includes? body "not-really-a-png")))))))

(deftest keyless-backend-round-trip
  (let [seen (atom nil)]
    (with-server
      (fn [req]
        (reset! seen req)
        ;; no type tags, no usage, an extra field: a looser dialect
        (assoc-in (json-reply 200 {"answers" {"q" {"noul" 0.9}}
                                   "routing" {"node" "a"}})
                  [:headers "x-request-id"] "abc-123"))
      (fn [base-url]
        (let [result (jev/ask (jev/client {:base-url base-url :model "local-model"})
                              (apply str (repeat 400 "lorem ipsum "))
                              {:q (jev/noul "?")})]
          (is (= {:answers {:q {:type :noul :noul 0.9}} :routing {"node" "a"}} result))
          (is (= "abc-123" (:request-id (meta result))))
          (is (not (contains? (:headers @seen) "authorization")))
          (is (not (contains? (:headers @seen) "content-encoding"))))))))
