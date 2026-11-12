# cljev

A Clojure and babashka client for [Jev](https://docs.typesafe.ai/introduction),
TypeSafe's System One model. You send a `state` and a set of typed questions
about it, and get back structured answers with probabilities.

It covers the two endpoints in the
[TypeSafe OpenAPI spec](https://api.typesafe.ai/openapi.json):
`POST /v1/systemone` and `GET /v1/models`.

## Usage

Set `TYPESAFE_API_KEY`, then:

```clojure
(require '[cljev.core :as jev])

(def client (jev/client))

(jev/ask client
         "I was charged twice for my subscription this month. Please refund it."
         {:billing (jev/noul "Is this message about billing?")
          :tone    (jev/choice "What is the tone of the message?"
                               [:angry :calm :excited])
          :urgency (jev/score "How urgent is this?"
                              ["Can wait"
                               "Needs attention this week"
                               "Needs attention today"])})
;; => {:model "jev-1.14.0"
;;     :answers {:billing {:type :noul :noul 0.98}
;;               :tone    {:type :choice
;;                         :choice :calm
;;                         :confidence 0.7
;;                         :probabilities {:angry 0.15 :calm 0.8 :excited 0.05}}
;;               :urgency {:type :score
;;                         :score 1.7
;;                         :confidence 0.55
;;                         :legend {0 "Can wait"
;;                                  1 "Needs attention this week"
;;                                  2 "Needs attention today"}
;;                         :probabilities {0 0.1 1 0.1 2 0.8}}}
;;     :usage {:input-tokens 120 :output-tokens 12}}
```

`state` is a string, a map, or a sequential collection. Answers are keyed by
the question names you gave, and choice answers use your option names, so
keywords come back as keywords and strings as strings. Score levels are keyed
by their integer position.

For a single question, `ask-one` returns the answer itself, with the model and
usage in its metadata:

```clojure
(jev/ask-one client "The package arrived crushed." (jev/noul "Is this a complaint?"))
;; => {:type :noul :noul 0.97}
```

### Questions

| Constructor | Question | Answer fields |
| - | - | - |
| `(noul instructions)` or `(noul instructions {:true .. :false ..})` | Yes or no | `:noul`, the probability of yes |
| `(choice instructions options)` | One of 1 to 255 options | `:choice`, `:confidence`, `:probabilities` |
| `(score instructions levels)` | A rating on 1 to 10 ordered levels | `:score`, `:confidence`, `:legend`, `:probabilities` |
| `(box instructions)` | Where something is in an image | `:box` with `:top :bottom :left :right`, `:confidence` |

`choice` options are a map of name to description, or a collection of names
with no descriptions. Instructions, option descriptions, and level
descriptions can be strings, maps, or vectors.

The constructors return plain maps, and `ask` accepts hand-written maps in the
same shape as the HTTP API (`{:type :noul :instructions "..."}`).

### Request options

`ask` and `ask-one` take an options map as a last argument:

```clojure
(jev/ask client state questions
         {:model "jev-1.14.0"   ; default is the client's model, jev-latest
          :long-context? true   ; TypeSafe-Danger-Zone-Long-Context: true
          :timeout-ms 30000
          :headers {"x-request-id" "abc"}})
```

`jev-latest` is an alias that moves when a release ships. Pass a versioned id
to pin the model, per request or as `:model` on the client.

### Images

Images are sent as assets keyed by a placeholder string. The placeholder is
replaced by the image wherever it appears in a string in the state or a
question. Requests with assets or box questions need `:images? true`, which
sends the `TypeSafe-Danger-Zone-Images: true` header.

```clojure
(let [ref (str (random-uuid))]
  (jev/ask client
           {:photo ref}
           {:cat?  (jev/noul "Is there a cat in the photo?")
            :where (jev/box "the cat")}
           {:assets {ref (jev/image "cat.png")}
            :images? true}))
```

`image` takes a path, a file, or a byte array with a format (`:png`, `:jpg`,
`:jpeg`). Images are base64-encoded into the JSON body; multipart upload is
not implemented.

### Models

```clojure
(jev/models client)
;; => [{:name "jev-latest" :description "..." :release-date "2026-10-14"} ...]
```

### Client options

```clojure
(jev/client {:api-key "..."        ; default: TYPESAFE_API_KEY
             :base-url "..."       ; default: TYPESAFE_BASE_URL, then https://api.typesafe.ai
             :model "jev-1.14.0"   ; default: TYPESAFE_DEFAULT_MODEL, then jev-latest
             :timeout-ms 10000
             :retry {:max-retries 2
                     :backoff-initial-ms 500
                     :backoff-max-ms 5000
                     :backoff-jitter 0.25}
             :headers {"x-team" "support"}})
```

The values shown for `:timeout-ms` and `:retry` are the defaults, which match
the TypeSafe Python SDK. The API key is stored in the client's metadata, so
printing the client does not print the key.

### Retries and errors

Responses with status 408, 429, or 5xx (including 529) and connection
failures are retried with exponential backoff. `retry-after` and
`retry-after-ms` response headers set the wait when present.

Failures are thrown as `ex-info`:

| `:type` in `ex-data` | When | Other keys |
| - | - | - |
| `:cljev.core/api-error` | Non-2xx reply after retries | `:status`, `:detail`, `:body`, `:headers` |
| `:cljev.core/connection-error` | Server unreachable or timed out after retries | `:attempts`, with the cause attached |
| `:cljev.core/invalid-question` | Too few or too many choice options or score levels | |
| `:cljev.core/invalid-asset` | Unsupported image format | |
| `:cljev.core/invalid-request` | `ask` called with no questions | |
| `:cljev.core/missing-api-key` | No API key given or in the environment | |

## Examples

`examples/` has babashka scripts. Run them from the project root with
`TYPESAFE_API_KEY` set:

| Script | What it does |
| - | - |
| `bb examples/models.clj` | Lists the models the account can use. A quick check that the key works. |
| `bb examples/triage.clj [MESSAGE]` | Asks a noul, a choice, and a score about a support message in one request. |
| `bb examples/classify.clj LABEL LABEL ... < lines` | Labels each line of stdin, one request per line, several at a time. Marks low-confidence labels. |
| `bb examples/grep.clj DESCRIPTION < lines` | Prints the lines of stdin that fit a description, using one yes/no question per line in a single request. |

```
git log --format=%s -20 | bb examples/classify.clj feature fix docs chore
bb examples/grep.clj "mentions a deadline" < notes.txt
```

## Not implemented

- Multipart image upload
- Request body compression (`gzip`, `zstd`)
- An async API; use `future` or virtual threads to fan out requests

## Development

Tasks run through babashka:

```
bb test        # tests under babashka
bb test:jvm    # tests on the JVM
bb lint        # clj-kondo
bb fmt         # cljfmt fix
bb fmt:check   # cljfmt check
bb ci          # fmt:check, lint, and both test runs
```

The tests use a stubbed request function and a local http-kit server. None of
them call the TypeSafe API.
