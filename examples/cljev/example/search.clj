(ns cljev.example.search
  "Search the files under some paths by meaning: print the passages most
  likely to answer a question.

      bb -m cljev.example.search \"where are idle sessions cleaned up\" src docs
      TOP=10 bb -m cljev.example.search \"how is the retry delay chosen\" src
      PREFILTER=60 bb -m cljev.example.search \"what limits request size\" .

  Each file is split into overlapping windows of lines. A batch of windows
  goes in the state, each under its own id, with one yes/no question per
  window, so a batch costs one request. The windows are then ranked by the
  probability of yes.

  There is no index: every search reads every window, so the cost grows with
  the size of the corpus. PREFILTER=N keeps only the N windows a keyword
  ranking puts first before asking, which bounds the cost and loses the
  windows that share no words with the query.

  Environment:

    TOP        Passages to print. Default 5.
    PREFILTER  Keep this many windows by keyword ranking. Default: all.
    WINDOW     Lines per window. Default 30.
    OVERLAP    Lines shared by neighboring windows. Default 10."
  (:require [cljev.core :as jev]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def text-extensions
  #{"clj" "cljc" "cljs" "bb" "edn" "md" "txt" "json" "yml" "yaml" "toml"
    "py" "rb" "go" "rs" "js" "ts" "java" "ex" "exs" "sh" "html" "css"})

(def max-file-bytes 500000)

(def batch-chars
  "Characters of window text per request. At three to four characters a
  token this stays well inside the model's per-question context."
  40000)

(def concurrency 4)

;; ---------------------------------------------------------------------------
;; Files and windows

(defn- extension [^java.io.File f]
  (some-> (re-find #"\.([^.]+)$" (.getName f)) second str/lower-case))

(defn- hidden? [^java.io.File f]
  (some #(and (str/starts-with? % ".") (not (#{"." ".."} %)))
        (str/split (.getPath f) #"/")))

(defn text-files
  "The text files under `paths`, by extension, skipping hidden directories
  and files over `max-file-bytes`."
  [paths]
  (->> paths
       (mapcat #(file-seq (io/file %)))
       (filter (fn [^java.io.File f]
                 (and (.isFile f)
                      (text-extensions (extension f))
                      (not (hidden? f))
                      (<= (.length f) max-file-bytes))))
       (map #(.getPath ^java.io.File %))
       distinct
       sort))

(defn windows
  "Split `text` into windows of `size` lines, each sharing `overlap` lines
  with the one before. Returns maps of :file, :start and :end (1-based line
  numbers, inclusive), and :text. Windows with no text are dropped."
  [file text {:keys [size overlap] :or {size 30 overlap 10}}]
  (let [lines (vec (str/split-lines text))
        step (max 1 (- size overlap))]
    (->> (range 0 (max 1 (count lines)) step)
         (map (fn [start]
                (let [end (min (count lines) (+ start size))]
                  {:file file
                   :start (inc start)
                   :end end
                   :text (str/join "\n" (subvec lines start end))})))
         ;; a final window that only repeats the overlap adds nothing
         (remove (fn [{:keys [start end text]}]
                   (or (str/blank? text)
                       (and (> start 1) (<= (- end start -1) overlap)))))
         vec)))

(defn corpus
  "The windows of every text file under `paths`."
  [paths opts]
  (vec (mapcat #(windows % (slurp %) opts) (text-files paths))))

;; ---------------------------------------------------------------------------
;; Keyword ranking

(def ^:private stopwords
  #{"the" "and" "for" "are" "does" "how" "what" "where" "when" "why" "who"
    "which" "that" "this" "with" "from" "into" "its" "can" "has" "have" "was"
    "will" "not" "but" "one" "any" "all" "get" "got" "use" "used" "after"
    "before" "while" "long" "many" "much" "about" "each" "every" "their"
    "them" "then" "than" "there" "would" "should" "could" "happens" "done"})

(defn terms
  "The lowercase words of `s`, of three letters or more, without stopwords."
  [s]
  (->> (re-seq #"[a-z0-9]+" (str/lower-case s))
       (filter #(>= (count %) 3))
       (remove stopwords)))

(defn keyword-ranked
  "Rank `chunks` by how well their words match the query's, best first, as
  [chunk score] pairs. The score is BM25 without length normalization:
  a word counts for more the fewer chunks it appears in, and repeating it
  counts for less each time."
  [query chunks]
  (let [query-terms (distinct (terms query))
        counts (mapv #(frequencies (terms (:text %))) chunks)
        n (count chunks)
        idf (into {}
                  (map (fn [t]
                         (let [df (count (filter #(contains? % t) counts))]
                           [t (Math/log (+ 1.0 (/ (+ (- n df) 0.5) (+ df 0.5))))])))
                  query-terms)]
    (->> (map (fn [chunk tf]
                [chunk (reduce + 0.0
                               (map (fn [t]
                                      (let [f (get tf t 0)]
                                        (* (idf t) (/ (* f 2.2) (+ f 1.2)))))
                                    query-terms))])
              chunks counts)
         (sort-by second >)
         vec)))

;; ---------------------------------------------------------------------------
;; Asking

(defn batches
  "Group `chunks` so that each group holds about `max-chars` of text."
  [chunks max-chars]
  (loop [chunks chunks, batch [], size 0, out []]
    (if-let [chunk (first chunks)]
      (let [n (count (:text chunk))]
        (if (and (seq batch) (> (+ size n) max-chars))
          (recur chunks [] 0 (conj out batch))
          (recur (rest chunks) (conj batch chunk) (+ size n) out)))
      (cond-> out (seq batch) (conj batch)))))

(defn- question [query id]
  (jev/noul (str "Would the passage `" id "` help someone answer this question: "
                 query)))

(defn- ask-batch
  "Ask about one batch. Returns {:scored [[chunk probability] ..] :usage ..},
  or {:error e} when the request fails."
  [client query batch]
  (let [ids (mapv #(str "p" %) (range (count batch)))
        state (zipmap ids (map (fn [{:keys [file text]}] (str file "\n" text)) batch))
        questions (into {} (map (fn [id] [id (question query id)])) ids)]
    (try
      (let [{:keys [answers usage]} (jev/ask client state questions)]
        {:scored (mapv (fn [id chunk] [chunk (:noul (get answers id))]) ids batch)
         :usage usage})
      (catch clojure.lang.ExceptionInfo e
        {:error e}))))

(defn search
  "Rank `chunks` by how likely each is to help answer `query`, best first.

  Returns {:hits [[chunk probability] ..] :stats ..}. The stats are the
  windows asked about, the requests made, the input tokens they used, the
  elapsed milliseconds, and any errors; a failed batch is left out of the
  hits.

  Options:

    :prefilter  Ask only about this many windows, chosen by keyword ranking.
    :max-chars  Characters of window text per request. Default `batch-chars`."
  [client query chunks {:keys [prefilter max-chars]}]
  (let [started (System/currentTimeMillis)
        asked (if prefilter
                (mapv first (take prefilter (keyword-ranked query chunks)))
                chunks)
        groups (batches asked (or max-chars batch-chars))
        results (->> (partition-all concurrency groups)
                     (mapcat (fn [group]
                               (->> group
                                    (mapv #(future (ask-batch client query %)))
                                    (mapv deref)))))]
    {:hits (->> results (mapcat :scored) (sort-by second >) vec)
     :stats {:windows (count asked)
             :requests (count groups)
             :input-tokens (reduce + 0 (keep #(get-in % [:usage :input-tokens]) results))
             :ms (- (System/currentTimeMillis) started)
             :errors (vec (keep #(some-> (:error %) ex-message) results))}}))

;; ---------------------------------------------------------------------------
;; Printing

(def price-per-million-tokens
  "USD per million input tokens for Jev, from TypeSafe's models page."
  0.042)

(defn- first-line [text]
  (let [line (or (first (remove str/blank? (str/split-lines text))) "")]
    (subs line 0 (min 100 (count line)))))

(defn print-search
  "Search `chunks` and print the `top` hits, then what the search cost."
  [client query chunks top opts]
  (let [{:keys [hits stats]} (search client query chunks opts)
        {:keys [windows requests input-tokens ms errors]} stats]
    (doseq [[{:keys [file start end text]} p] (take top hits)]
      (println (format "%.2f  %s:%d-%d  %s" (double p) file start end (first-line text))))
    (binding [*out* *err*]
      (doseq [e errors]
        (println "failed batch:" e))
      (println (format "%d windows, %d requests, %d input tokens (about $%.4f), %.1fs"
                       windows requests input-tokens
                       (* price-per-million-tokens (/ input-tokens 1e6))
                       (/ ms 1000.0))))))

(defn- env-long [k default]
  (or (some-> (System/getenv k) parse-long) default))

(defn -main [& [query & paths]]
  (when (or (str/blank? query) (empty? paths))
    (binding [*out* *err*]
      (println "usage: bb -m cljev.example.search QUERY PATH [PATH ...]"))
    (System/exit 2))
  (let [chunks (corpus paths {:size (env-long "WINDOW" 30)
                              :overlap (env-long "OVERLAP" 10)})]
    (print-search (jev/client {:timeout-ms 60000})
                  query
                  chunks
                  (env-long "TOP" 5)
                  {:prefilter (env-long "PREFILTER" nil)})
    (shutdown-agents)))
