(ns cljev.core
  "Client for the TypeSafe System One API and its Jev models.

  Runs on the JVM and on babashka.

    (require '[cljev.core :as jev])

    (def client (jev/client))            ; reads TYPESAFE_API_KEY

    (jev/ask client
             \"I was charged twice this month. Please refund the extra charge.\"
             {:billing (jev/noul \"Is this message about billing?\")
              :tone    (jev/choice \"What is the tone?\" [:angry :calm :excited])})
    ;; => {:model \"jev-1.14.0\"
    ;;     :answers {:billing {:type :noul :noul 0.98}
    ;;               :tone    {:type :choice :choice :calm :confidence 0.7
    ;;                         :probabilities {:angry 0.15 :calm 0.8 :excited 0.05}}}
    ;;     :usage {:input-tokens 120 :output-tokens 12}}"
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files)
           (java.util Base64)))

(def default-base-url "https://api.typesafe.ai")
(def default-model "jev-latest")
(def default-timeout-ms 10000)

(def default-retry
  "Default retry policy. Delays are in milliseconds. `:backoff-jitter` is the
  fraction of each delay that may be randomly subtracted."
  {:max-retries 2
   :backoff-initial-ms 500
   :backoff-max-ms 5000
   :backoff-jitter 0.25})

(def max-choice-options 255)
(def max-score-levels 10)

;; ---------------------------------------------------------------------------
;; Questions

(defn- key->str
  "The JSON object key a Clojure map key is sent as."
  [k]
  (cond
    (keyword? k) (subs (str k) 1)
    (string? k) k
    :else (str k)))

(defn noul
  "A yes/no question. The answer is the probability that the answer is yes.

  `criteria` is optional: a map with `:true` and/or `:false` describing what
  counts as yes and as no."
  ([instructions] {:type :noul :instructions instructions})
  ([instructions criteria]
   {:type :noul :instructions instructions :criteria criteria}))

(defn choice
  "A question that selects one option.

  `options` is either a map of option name to a description of when it
  applies, or a collection of option names with no descriptions. Names may be
  keywords or strings; the answer uses the names as you gave them."
  [instructions options]
  (let [criteria (if (map? options)
                   options
                   (into (array-map) (map (fn [o] [o nil])) options))
        n (count criteria)]
    (when-not (<= 1 n max-choice-options)
      (throw (ex-info (str "A choice question takes 1 to " max-choice-options
                           " options, got " n)
                      {:type ::invalid-question :options n})))
    {:type :choice :instructions instructions :criteria criteria}))

(defn score
  "A question that rates content against ordered levels.

  `levels` is a sequential collection of level descriptions, lowest first. A
  level's position is its score, starting at zero."
  [instructions levels]
  (let [levels (vec levels)
        n (count levels)]
    (when-not (<= 1 n max-score-levels)
      (throw (ex-info (str "A score question takes 1 to " max-score-levels
                           " levels, got " n)
                      {:type ::invalid-question :levels n})))
    {:type :score :instructions instructions :criteria levels}))

(defn box
  "A question that locates what `instructions` describe in the request's
  image. Needs a model with Box support and `:images? true` on the request."
  [instructions]
  {:type :box :instructions instructions})

;; ---------------------------------------------------------------------------
;; Assets

(defn- read-bytes ^bytes [src]
  (if (bytes? src)
    src
    (Files/readAllBytes (.toPath (io/file src)))))

(defn- format-from-name [src]
  (when-not (bytes? src)
    (some-> (re-find #"\.([A-Za-z]+)$" (str src)) second str/lower-case)))

(defn image
  "An image asset for the `:assets` request option.

  `src` is a byte array, a file, or a path. `fmt` is \"png\", \"jpg\", or
  \"jpeg\"; it is taken from the file extension when omitted.

  Assets are keyed by a placeholder string, and the placeholder is replaced by
  the image wherever it appears in a string in the state or in a question:

    (let [ref (str (random-uuid))]
      (jev/ask client {:photo ref} questions
               {:assets {ref (jev/image \"cat.png\")} :images? true}))"
  ([src] (image src nil))
  ([src fmt]
   (let [fmt (some-> (or fmt (format-from-name src)) name str/lower-case)]
     (when-not (#{"png" "jpg" "jpeg"} fmt)
       (throw (ex-info "Image format must be png, jpg, or jpeg"
                       {:type ::invalid-asset :format fmt})))
     {:type "image"
      :format fmt
      :data_base64 (.encodeToString (Base64/getEncoder) (read-bytes src))})))

;; ---------------------------------------------------------------------------
;; Client

(defn client
  "Create a client. All options are optional:

    :api-key      API key. Default: the TYPESAFE_API_KEY environment variable.
    :base-url     Default: TYPESAFE_BASE_URL, then https://api.typesafe.ai.
    :model        Default model for `ask`. Default: TYPESAFE_DEFAULT_MODEL,
                  then \"jev-latest\". An alias such as jev-latest moves when a
                  release ships; pass a versioned id such as \"jev-1.14.0\" to
                  pin.
    :timeout-ms   Timeout for connecting and for each request. Default 10000.
    :retry        Map merged over `default-retry`. `{:max-retries 0}` turns
                  retries off.
    :headers      Extra headers sent with every request.
    :http-client  A babashka.http-client client to use instead of a new one.
    :request-fn   Replacement for `babashka.http-client/request`. For tests.
    :sleep-fn     Function of milliseconds used to wait between retries.

  The client is a plain map. The API key is kept in its metadata so that
  printing the client does not print the key."
  ([] (client {}))
  ([{:keys [api-key base-url model timeout-ms retry headers
            http-client request-fn sleep-fn]}]
   (let [api-key (or api-key (System/getenv "TYPESAFE_API_KEY"))
         timeout-ms (or timeout-ms default-timeout-ms)]
     (when (str/blank? api-key)
       (throw (ex-info "No API key: pass :api-key or set TYPESAFE_API_KEY"
                       {:type ::missing-api-key})))
     (with-meta
       {:base-url (str/replace (or base-url
                                   (System/getenv "TYPESAFE_BASE_URL")
                                   default-base-url)
                               #"/+$" "")
        :model (or model
                   (System/getenv "TYPESAFE_DEFAULT_MODEL")
                   default-model)
        :timeout-ms timeout-ms
        :retry (merge default-retry retry)
        :headers headers
        :http-client (or http-client
                         (when-not request-fn
                           (http/client (assoc http/default-client-opts
                                               :connect-timeout timeout-ms))))
        :request-fn (or request-fn http/request)
        :sleep-fn (or sleep-fn (fn [ms] (Thread/sleep (long ms))))}
       {::api-key api-key}))))

;; ---------------------------------------------------------------------------
;; HTTP

(defn- retryable-status? [status]
  (or (= 408 status) (= 429 status) (<= 500 status 599)))

(defn- backoff-ms [{:keys [backoff-initial-ms backoff-max-ms backoff-jitter]} attempt]
  (let [base (min backoff-max-ms
                  (* backoff-initial-ms (bit-shift-left 1 (min attempt 20))))]
    (long (* base (- 1.0 (* backoff-jitter (rand)))))))

(defn- retry-after-ms
  "The delay the server asked for, from `retry-after-ms` or `retry-after`
  (seconds), or nil."
  [{:keys [headers]}]
  (letfn [(number [h] (some-> (get headers h) str parse-double))]
    (some-> (or (number "retry-after-ms")
                (some-> (number "retry-after") (* 1000)))
            (max 0)
            long)))

(defn- parse-body [body]
  (when-not (str/blank? body)
    (try
      (json/parse-string body)
      (catch Exception _ body))))

(defn- detail-message [detail]
  (cond
    (string? detail) detail
    (sequential? detail)
    (str/join "; " (map (fn [e]
                          (if (map? e)
                            (str (str/join "." (get e "loc")) ": " (get e "msg"))
                            (str e)))
                        detail))
    (nil? detail) nil
    :else (json/generate-string detail)))

(defn- api-error [{:keys [status headers body]}]
  (let [parsed (parse-body body)
        detail (if (map? parsed) (get parsed "detail") parsed)
        message (detail-message detail)]
    (ex-info (str "TypeSafe API error " status (when message (str ": " message)))
             {:type ::api-error
              :status status
              :detail detail
              :body parsed
              :headers headers})))

(defn- send!
  "Send `req`, retrying per the client's retry policy. Returns the response of
  a 2xx reply and throws an ex-info for anything else."
  [{:keys [request-fn sleep-fn retry]} req]
  (loop [attempt 0]
    (let [result (try
                   (request-fn req)
                   (catch java.io.IOException e e))
          retry? (< attempt (:max-retries retry))]
      (cond
        (instance? Throwable result)
        (if retry?
          (do (sleep-fn (backoff-ms retry attempt))
              (recur (inc attempt)))
          (throw (ex-info (str "TypeSafe request failed: "
                               (or (ex-message result) (.getName (class result))))
                          {:type ::connection-error :attempts (inc attempt)}
                          result)))

        (<= 200 (:status result) 299)
        result

        (and retry? (retryable-status? (:status result)))
        (do (sleep-fn (or (retry-after-ms result) (backoff-ms retry attempt)))
            (recur (inc attempt)))

        :else
        (throw (api-error result))))))

(defn- request
  [client method path {:keys [body headers timeout-ms]}]
  (let [req (cond-> {:method method
                     :uri (str (:base-url client) path)
                     :headers (merge {"accept" "application/json"}
                                     (when body {"content-type" "application/json"})
                                     (:headers client)
                                     headers
                                     {"authorization"
                                      (str "Bearer " (::api-key (meta client)))})
                     :timeout (or timeout-ms (:timeout-ms client))
                     :throw false}
              (:http-client client) (assoc :client (:http-client client))
              body (assoc :body (json/generate-string body)))]
    (parse-body (:body (send! client req)))))

;; ---------------------------------------------------------------------------
;; Responses

(defn- field-key [s]
  (keyword (str/replace s "_" "-")))

(defn- update-some [m k f & args]
  (if (some? (get m k))
    (apply update m k f args)
    m))

(defn- key-lookup
  "Map from the JSON key each key of `m` was sent as back to the original."
  [m]
  (into {} (map (juxt key->str identity)) (keys m)))

(defn- restore-keys [m lookup]
  (update-keys m #(get lookup % %)))

(defn- level-key [s]
  (or (parse-long s) s))

(defn- parse-answer [question answer]
  (let [answer (-> (update-keys answer field-key)
                   (update-some :type keyword))]
    (case (:type answer)
      :choice (let [criteria (or (:criteria question) (get question "criteria"))
                    lookup (if (map? criteria) (key-lookup criteria) {})]
                (-> answer
                    (update-some :choice #(get lookup % %))
                    (update-some :probabilities restore-keys lookup)))
      :score (-> answer
                 (update-some :legend update-keys level-key)
                 (update-some :probabilities update-keys level-key))
      :box (update-some answer :box update-keys field-key)
      answer)))

(defn- parse-system-one [questions response]
  (let [lookup (key-lookup questions)]
    (-> (update-keys response field-key)
        (update-some :usage update-keys field-key)
        (update-some :answers
                     (fn [answers]
                       (into {}
                             (map (fn [[k answer]]
                                    (let [k (get lookup k k)]
                                      [k (parse-answer (get questions k) answer)])))
                             answers))))))

;; ---------------------------------------------------------------------------
;; API

(defn ask
  "Ask `questions` about `state` in one request.

  `state` is a string, a map, or a sequential collection. `questions` is a map
  of a name you choose to a question (see `noul`, `choice`, `score`, `box`).

  Returns `{:model .. :answers .. :usage ..}`. `:answers` is keyed by the
  names in `questions`, as you gave them. Each answer has a `:type` and:

    :noul    :noul, the probability of yes
    :choice  :choice, :confidence, :probabilities keyed by option name
    :score   :score, :confidence, :legend and :probabilities keyed by level
    :box     :box with :top :bottom :left :right, and :confidence

  Options:

    :model          Model for this request. Default: the client's model.
    :assets         Map of placeholder string to asset (see `image`).
    :images?        Send `TypeSafe-Danger-Zone-Images: true`. Required for
                    assets and box questions.
    :long-context?  Send `TypeSafe-Danger-Zone-Long-Context: true`.
    :timeout-ms     Timeout for this request.
    :headers        Extra headers for this request.

  Throws an ex-info whose `:type` is `:cljev.core/api-error` (with `:status`,
  `:detail`, `:body`) for a non-2xx reply, or `:cljev.core/connection-error`
  when the server could not be reached."
  ([client state questions] (ask client state questions nil))
  ([client state questions
    {:keys [model assets images? long-context? timeout-ms headers]}]
   (when (empty? questions)
     (throw (ex-info "ask needs at least one question"
                     {:type ::invalid-request})))
   (->> {:body (cond-> {:model (or model (:model client))
                        :state state
                        :questions questions}
                 (seq assets) (assoc :assets assets))
         :headers (cond-> (or headers {})
                    images? (assoc "typesafe-danger-zone-images" "true")
                    long-context? (assoc "typesafe-danger-zone-long-context" "true"))
         :timeout-ms timeout-ms}
        (request client :post "/v1/systemone")
        (parse-system-one questions))))

(defn ask-one
  "Ask a single `question` about `state` and return its answer. Takes the same
  options as `ask`. The model and usage are in the answer's metadata."
  ([client state question] (ask-one client state question nil))
  ([client state question opts]
   (let [response (ask client state {:q question} opts)]
     (with-meta (get-in response [:answers :q])
       (select-keys response [:model :usage])))))

(defn models
  "List the models and aliases available to the account, as a vector of
  `{:name .. :description .. :release-date ..}`."
  [client]
  (mapv #(update-keys % field-key)
        (get (request client :get "/v1/models" nil) "models")))
