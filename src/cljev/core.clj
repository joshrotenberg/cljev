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
  (:import (java.io ByteArrayOutputStream)
           (java.nio.file Files)
           (java.util Base64)
           (java.util.concurrent Executors Future)
           (java.util.zip GZIPOutputStream)))

(def version
  "The version of this library, sent in the user-agent header."
  "0.1.2") ; x-release-please-version

(def default-base-url "https://api.typesafe.ai")
(def default-model "jev-latest")
(def default-timeout-ms 10000)

(def default-retry
  "Default retry policy. Delays are in milliseconds.

    :max-retries         Retries after the first attempt.
    :backoff-initial-ms  First delay; doubled on each retry.
    :backoff-max-ms      Upper bound on the backoff delay.
    :backoff-jitter      Fraction of each delay that may be randomly subtracted.
    :max-retry-after-ms  Longest delay a retry-after header may ask for. A
                         reply that asks for more is not retried."
  {:max-retries 2
   :backoff-initial-ms 500
   :backoff-max-ms 5000
   :backoff-jitter 0.25
   :max-retry-after-ms 60000})

(def max-choice-options 255)
(def max-score-levels 10)

(def compression-threshold
  "Request bodies of at least this many bytes are compressed."
  1024)

;; ---------------------------------------------------------------------------
;; Questions

(defn- key->str
  "The JSON object key a Clojure map key is sent as."
  [k]
  (cond
    (keyword? k) (subs (str k) 1)
    (string? k) k
    :else (str k)))

(defn- distinct-names?
  "Whether `names` are still distinct as the JSON keys they are sent as."
  [names]
  (or (empty? names)
      (apply distinct? (map key->str names))))

(defn noul
  "A yes/no question. The answer is the probability that the answer is yes.

  `criteria` is optional: a map with `:true` and/or `:false` describing what
  counts as yes and as no."
  ([instructions] {:type :noul :instructions instructions})
  ([instructions criteria]
   {:type :noul :instructions instructions :criteria criteria}))

(defn- option-pair [option]
  (if (vector? option)
    (if (= 2 (count option))
      option
      (throw (ex-info "A choice option is a name or a [name description] pair"
                      {:type ::invalid-question :option option})))
    [option nil]))

(defn choice
  "A question that selects one option.

  `options` is one of:

    - a collection of option names:        [:angry :calm]
    - a collection of [name description]:  [[:angry \"Hostile\"] [:calm \"Neutral\"]]
    - a map of name to description:        {:angry \"Hostile\" :calm \"Neutral\"}

  Names may be keywords or strings; the answer uses the names as you gave
  them. A collection may mix bare names and pairs.

  Options are sent in the order given, and the order can affect the answer:
  Jev can lean toward the first option. A collection keeps its order for any
  number of options. A Clojure map only keeps its order up to 8 entries, so
  pass a collection of pairs for a larger set with descriptions."
  [instructions options]
  (let [criteria (if (map? options)
                   options
                   (let [pairs (map option-pair options)
                         ordered (apply array-map (mapcat identity pairs))]
                     (when-not (= (count ordered) (count pairs))
                       (throw (ex-info "Choice option names must be distinct"
                                       {:type ::invalid-question})))
                     ordered))
        n (count criteria)]
    (when-not (distinct-names? (keys criteria))
      (throw (ex-info (str "Choice option names must be distinct once written "
                           "as strings: :a and \"a\" are the same name")
                      {:type ::invalid-question})))
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
;; Images

(defrecord Image [format bytes ref])

(defn- image? [x]
  (instance? Image x))

(defn- read-bytes ^bytes [src]
  (if (bytes? src)
    src
    (Files/readAllBytes (.toPath (io/file src)))))

(defn- format-from-name [src]
  (when-not (bytes? src)
    (some-> (re-find #"\.([A-Za-z]+)$" (str src)) second str/lower-case)))

(defn image
  "An image to send with a request.

  `src` is a byte array, a file, or a path. `fmt` is \"png\", \"jpg\", or
  \"jpeg\"; it is taken from the file extension when omitted.

  Put the image anywhere a value goes in the state or in a question's
  instructions or criteria:

    (jev/ask client {:photo (jev/image \"cat.png\")} questions {:images? true})

  To use it inside a string, write its `:ref` into the string and pass the
  image in the `:assets` option:

    (let [img (jev/image \"cat.png\")]
      (jev/ask client (str \"The photo: \" (:ref img)) questions
               {:assets [img] :images? true}))

  Each image gets a unique `:ref` when it is created. Using one image in
  several places uploads it once."
  ([src] (image src nil))
  ([src fmt]
   (let [fmt (some-> (or fmt (format-from-name src)) name str/lower-case)]
     (when-not (#{"png" "jpg" "jpeg"} fmt)
       (throw (ex-info "Image format must be png, jpg, or jpeg"
                       {:type ::invalid-asset :format fmt})))
     (->Image fmt (read-bytes src) (str (random-uuid))))))

(defn- replace-images
  "Return `x` with every image in it replaced by its ref, recording each
  image in the volatile `found`. Maps keep their type and entry order."
  [found x]
  (cond
    (image? x) (do (vswap! found assoc (:ref x) x)
                   (:ref x))
    (map? x) (reduce-kv (fn [m k v]
                          (let [v' (replace-images found v)]
                            (if (identical? v v') m (assoc m k v'))))
                        x x)
    (vector? x) (mapv #(replace-images found %) x)
    (set? x) (into #{} (map #(replace-images found %)) x)
    (sequential? x) (mapv #(replace-images found %) x)
    :else x))

(defn- named-assets
  "The `:assets` option as a map of ref to asset."
  [assets]
  (cond
    (nil? assets) {}
    (map? assets) (update-keys assets key->str)
    :else (into {}
                (map (fn [a]
                       (when-not (image? a)
                         (throw (ex-info (str ":assets is a map of reference to asset, "
                                              "or a collection of images")
                                         {:type ::invalid-asset})))
                       [(:ref a) a]))
                assets)))

(defn- content-type [fmt]
  (if (= "png" fmt) "image/png" "image/jpeg"))

(defn- encode-assets
  "Turn a map of ref to asset into the request's `assets` object and, for a
  multipart upload, the file parts that carry the image bytes."
  [assets upload]
  (reduce
   (fn [acc [i [ref asset]]]
     (cond
       (not (image? asset))
       (assoc-in acc [:assets ref] asset)

       (= :base64 upload)
       (assoc-in acc [:assets ref]
                 {:type "image"
                  :format (:format asset)
                  :data_base64 (.encodeToString (Base64/getEncoder)
                                                ^bytes (:bytes asset))})

       :else
       (let [part (str "image-" i)]
         (-> acc
             (assoc-in [:assets ref] {:type "image" :part part})
             (update :parts conj {:name part
                                  :file-name (str part "." (:format asset))
                                  :content-type (content-type (:format asset))
                                  :content (:bytes asset)})))))
   {:assets {} :parts []}
   (map-indexed vector assets)))

;; ---------------------------------------------------------------------------
;; Client

(defn- clean-api-key
  "The key with surrounding whitespace removed, or nil when there is none."
  [api-key]
  (let [api-key (some-> api-key str/trim not-empty)]
    (when (some->> api-key (re-find #"[^\x21-\x7E]"))
      (throw (ex-info (str "The API key contains whitespace, control, or "
                           "non-ASCII characters")
                      {:type ::invalid-api-key})))
    api-key))

(defn client
  "Create a client. All options are optional:

    :api-key      API key. Default: the TYPESAFE_API_KEY environment variable.
                  Surrounding whitespace is removed.
    :base-url     Default: TYPESAFE_BASE_URL, then https://api.typesafe.ai.
                  Point it at another server that implements the System One
                  API; see below.
    :model        Default model for `ask`. Default: TYPESAFE_DEFAULT_MODEL,
                  then \"jev-latest\". An alias such as jev-latest moves when a
                  release ships; pass a versioned id such as \"jev-1.14.0\" to
                  pin.
    :timeout-ms   Timeout for connecting and for each request. Default 10000.
    :retry        Map merged over `default-retry`. `{:max-retries 0}` turns
                  retries off.
    :compression  :gzip compresses request bodies of 1 KiB or more; nil or
                  false sends every body uncompressed. Default: :gzip for
                  the TypeSafe API, nil for another :base-url.
    :systemone-path  Path of the System One endpoint. Default /v1/systemone.
    :models-path     Path of the models endpoint. Default /v1/models.
    :headers      Extra headers sent with every request.
    :http-client  A babashka.http-client client to use instead of a new one.
    :request-fn   Replacement for `babashka.http-client/request`. For tests.
    :sleep-fn     Function of milliseconds used to wait between retries.

  Another backend: a `:base-url` other than TypeSafe's needs no API key, and
  without one no authorization header is sent. TYPESAFE_API_KEY is not used
  for a `:base-url` given here, so the TypeSafe key is never sent to another
  server; pass that server's key as `:api-key` if it needs one.

  The client is a plain map. The API key is kept in its metadata so that
  printing the client does not print the key."
  ([] (client {}))
  ([{:keys [api-key base-url model timeout-ms retry headers
            http-client request-fn sleep-fn systemone-path models-path]
     :as opts}]
   (let [url (str/replace (or base-url
                              (System/getenv "TYPESAFE_BASE_URL")
                              default-base-url)
                          #"/+$" "")
         typesafe? (= url default-base-url)
         ;; the environment's key belongs to the environment's server
         env-key (when (or typesafe? (nil? base-url))
                   (System/getenv "TYPESAFE_API_KEY"))
         api-key (clean-api-key (or api-key env-key))
         timeout-ms (or timeout-ms default-timeout-ms)
         compression (get opts :compression (when typesafe? :gzip))]
     (when (and typesafe? (nil? api-key))
       (throw (ex-info "No API key: pass :api-key or set TYPESAFE_API_KEY"
                       {:type ::missing-api-key})))
     (when-not (contains? #{:gzip nil false} compression)
       (throw (ex-info ":compression must be :gzip, nil, or false"
                       {:type ::invalid-option :compression compression})))
     (with-meta
       {:base-url url
        :systemone-path (or systemone-path "/v1/systemone")
        :models-path (or models-path "/v1/models")
        :model (or model
                   (System/getenv "TYPESAFE_DEFAULT_MODEL")
                   default-model)
        :timeout-ms timeout-ms
        :retry (merge default-retry retry)
        :compression (or compression nil)
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

(defn- request-id [{:keys [headers]}]
  (or (get headers "x-typesafe-request-id")
      (get headers "x-request-id")))

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

(defn- api-error [{:keys [status headers body] :as response}]
  (let [parsed (parse-body body)
        detail (if (map? parsed) (get parsed "detail") parsed)
        message (detail-message detail)
        id (request-id response)]
    (ex-info (str "TypeSafe API error " status
                  (when message (str ": " message))
                  (when id (str " (request " id ")")))
             {:type ::api-error
              :status status
              :detail detail
              :body parsed
              :headers headers
              :request-id id
              :retry-after-ms (retry-after-ms response)})))

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
        (let [asked (retry-after-ms result)]
          (when (and asked (> asked (:max-retry-after-ms retry)))
            (throw (api-error result)))
          (sleep-fn (or asked (backoff-ms retry attempt)))
          (recur (inc attempt)))

        :else
        (throw (api-error result))))))

(defn- gzip ^bytes [^bytes data]
  (let [out (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. out)]
      (.write gz data))
    (.toByteArray out)))

(defn- with-json-body
  "Add `body` to `req` as JSON, compressed when the client asks for it and
  the body is large enough."
  [client req body]
  (let [text (json/generate-string body)
        data (.getBytes text "UTF-8")
        req (assoc-in req [:headers "content-type"] "application/json")]
    (if (and (= :gzip (:compression client))
             (>= (alength data) compression-threshold))
      (-> req
          (assoc :body (gzip data))
          (assoc-in [:headers "content-encoding"] "gzip"))
      (assoc req :body text))))

(defn- with-multipart-body
  "Add `body` to `req` as the JSON part of a multipart request, followed by
  the file `parts`."
  [req body parts]
  (assoc req :multipart (into [{:name "request"
                                :content-type "application/json"
                                :content (json/generate-string body)}]
                              parts)))

(defn- lower-case-keys [headers]
  (update-keys headers (comp str/lower-case name)))

(defn- request
  "Send a request and return the response with its `:body` parsed."
  [client method path {:keys [body parts headers timeout-ms]}]
  (let [req (cond-> {:method method
                     :uri (str (:base-url client) path)
                     ;; names are lowercased so that a caller's header
                     ;; replaces the client's, or is replaced by it, and is
                     ;; never sent beside it; the body's content type is the
                     ;; client's to set
                     :headers (-> (merge {"accept" "application/json"
                                          "user-agent" (str "cljev/" version)}
                                         (lower-case-keys (:headers client))
                                         (lower-case-keys headers)
                                         (when-let [api-key (::api-key (meta client))]
                                           {"authorization" (str "Bearer " api-key)}))
                                  (dissoc "content-type"))
                     :timeout (or timeout-ms (:timeout-ms client))
                     :throw false}
              (:http-client client) (assoc :client (:http-client client)))
        req (cond
              (seq parts) (with-multipart-body req body parts)
              body (with-json-body client req body)
              :else req)]
    (update (send! client req) :body parse-body)))

(defn- response-meta
  "What a result carries in its metadata about the HTTP exchange."
  [response]
  {:request-id (request-id response)
   :response (select-keys response [:status :headers :body])})

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

(defn- answer-type
  "The type of an answer: its own `type` tag, or, for a server that leaves
  the tag out, the type of the question it answers, or the field that only
  one type of answer has."
  [question answer]
  (or (some-> (:type answer) keyword)
      (some-> (or (:type question) (get question "type")) name keyword)
      (some #(when (contains? answer %) %) [:noul :choice :score :box])))

(defn- parse-answer [question answer]
  (let [answer (update-keys answer field-key)
        type (answer-type question answer)
        answer (cond-> answer type (assoc :type type))]
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

  The result's metadata has `:request-id` and `:response`, the HTTP status,
  headers, and JSON body as received.

  Options:

    :model          Model for this request. Default: the client's model.
    :assets         Images referenced from strings: a collection of images
                    (see `image`), or a map of reference string to image or
                    to a raw asset map.
    :upload         How image bytes are sent: :multipart (the default) or
                    :base64, which puts them in the JSON body.
    :images?        Send `TypeSafe-Danger-Zone-Images: true`. Required for
                    images and box questions.
    :long-context?  Send `TypeSafe-Danger-Zone-Long-Context: true`.
    :extra-body     Map of extra top-level request fields, for API features
                    this client has no option for.
    :timeout-ms     Timeout for this request.
    :headers        Extra headers for this request.

  Throws an ex-info whose `:type` is `:cljev.core/api-error` (with `:status`,
  `:detail`, `:body`, `:request-id`) for a non-2xx reply, or
  `:cljev.core/connection-error` when the server could not be reached.

  See `verdict` and `sample` for acting on an answer."
  ([client state questions] (ask client state questions nil))
  ([client state questions
    {:keys [model assets upload images? long-context? extra-body timeout-ms headers]}]
   (when (empty? questions)
     (throw (ex-info "ask needs at least one question"
                     {:type ::invalid-request})))
   (when-not (distinct-names? (keys questions))
     (throw (ex-info (str "Question names must be distinct once written as "
                          "strings: :a and \"a\" are the same name")
                     {:type ::invalid-request})))
   (doseq [[question-name question] questions
           :let [criteria (when (map? question)
                            (or (:criteria question) (get question "criteria")))]
           :when (and (map? criteria) (not (distinct-names? (keys criteria))))]
     (throw (ex-info (str "The criteria of question " (pr-str question-name)
                          " have names that are the same once written as strings")
                     {:type ::invalid-question :question question-name})))
   (when-not (contains? #{nil :multipart :base64} upload)
     (throw (ex-info ":upload must be :multipart or :base64"
                     {:type ::invalid-option :upload upload})))
   (let [found (volatile! {})
         sent-state (replace-images found state)
         sent-questions (replace-images found questions)
         {:keys [assets parts]} (encode-assets (merge (named-assets assets) @found)
                                               upload)
         response (request client :post (:systemone-path client)
                           {:body (cond-> (merge extra-body
                                                 {:model (or model (:model client))
                                                  :state sent-state
                                                  :questions sent-questions})
                                    (seq assets) (assoc :assets assets))
                            :parts parts
                            :headers (cond-> (or headers {})
                                       images? (assoc "typesafe-danger-zone-images" "true")
                                       long-context? (assoc "typesafe-danger-zone-long-context" "true"))
                            :timeout-ms timeout-ms})]
     (with-meta (parse-system-one questions (:body response))
       (response-meta response)))))

(defn ask-one
  "Ask a single `question` about `state` and return its answer. Takes the same
  options as `ask`. The answer's metadata has the `:model`, `:usage`, and
  `:request-id`."
  ([client state question] (ask-one client state question nil))
  ([client state question opts]
   (let [response (ask client state {:q question} opts)]
     (with-meta (get-in response [:answers :q])
       (merge (select-keys response [:model :usage])
              (select-keys (meta response) [:request-id]))))))

(defn ask-many
  "Ask the same `questions` about each of `states`, one request per state,
  several at a time. Returns a vector of results in the order of `states`.

  A state whose request fails does not stop the others: its result is
  `{:error e}`, where `e` is the exception `ask` threw.

  Takes the options of `ask`, plus:

    :concurrency  Requests in flight at once. Default 8."
  ([client states questions] (ask-many client states questions nil))
  ([client states questions opts]
   (let [states (vec states)
         concurrency (max 1 (min (or (:concurrency opts) 8) (count states)))
         opts (dissoc opts :concurrency)]
     (if (empty? states)
       []
       (let [pool (Executors/newFixedThreadPool concurrency)]
         (try
           (let [tasks (mapv (fn [state]
                               (fn []
                                 (try
                                   (ask client state questions opts)
                                   (catch Exception e {:error e}))))
                             states)]
             (mapv (fn [^Future f] (.get f))
                   (.invokeAll pool ^java.util.Collection tasks)))
           (finally
             (.shutdown pool))))))))

(defn models
  "List the models and aliases available to the account, as a vector of
  `{:name .. :description .. :release-date ..}`. The vector's metadata has
  `:request-id` and `:response`.

  A server that lists models in the OpenAI shape, under `data` with an `id`,
  is read too: each entry gets its `:name` from its `id`."
  [client]
  (let [response (request client :get (:models-path client) nil)
        body (:body response)]
    (with-meta (mapv (fn [entry]
                       (let [entry (update-keys entry field-key)]
                         (cond-> entry
                           (and (nil? (:name entry)) (:id entry))
                           (assoc :name (:id entry)))))
                     (or (get body "models") (get body "data")))
      (response-meta response))))

;; ---------------------------------------------------------------------------
;; Decisions

(defn verdict
  "Read a yes/no answer as `:yes`, `:no`, or `:unsure`.

  `answer` is a noul answer or a probability of yes. `threshold`, from 0.5
  to 1, is how sure the answer must be either way: `:yes` when the
  probability of yes is at least `threshold`, `:no` when the probability of
  no is, and `:unsure` in between. The default, 0.5, is never unsure.

    (jev/verdict {:type :noul :noul 0.93} 0.8) ;; => :yes
    (jev/verdict {:type :noul :noul 0.60} 0.8) ;; => :unsure
    (jev/verdict {:type :noul :noul 0.10} 0.8) ;; => :no"
  ([answer] (verdict answer 0.5))
  ([answer threshold]
   (let [p (if (number? answer) answer (:noul answer))]
     (when-not (number? p)
       (throw (ex-info "verdict takes a noul answer or a probability"
                       {:type ::invalid-answer :answer answer})))
     (when-not (<= 0.5 threshold 1)
       (throw (ex-info "The verdict threshold is between 0.5 and 1"
                       {:type ::invalid-option :threshold threshold})))
     (cond
       (>= p threshold) :yes
       (>= (- 1 p) threshold) :no
       :else :unsure))))

(defn sample
  "Pick an option of a choice answer, or a level of a score answer, at random
  in proportion to its probability, where `:choice` always gives the
  likeliest one. An option with probability zero is never picked.

  `roll` is a number from 0 to 1 and defaults to `(rand)`; pass your own for
  a draw you can reproduce."
  ([answer] (sample answer (rand)))
  ([answer roll]
   (let [ranked (sort-by (juxt (comp - val) (comp str key)) (:probabilities answer))
         target (* roll (reduce + 0.0 (map val ranked)))]
     (when (empty? ranked)
       (throw (ex-info "sample takes an answer with :probabilities"
                       {:type ::invalid-answer :answer answer})))
     (loop [[[option p] & more] ranked
            seen 0.0]
       (let [seen (+ seen p)]
         (cond
           (< target seen) option
           (seq more) (recur more seen)
           ;; a roll at or past the end of the distribution
           :else (key (first ranked))))))))
