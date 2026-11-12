# cljev

[![ci](https://github.com/joshrotenberg/cljev/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/joshrotenberg/cljev/actions/workflows/ci.yml)
[![release](https://img.shields.io/github/v/release/joshrotenberg/cljev?sort=semver)](https://github.com/joshrotenberg/cljev/releases)
[![Clojars Project](https://img.shields.io/clojars/v/io.github.joshrotenberg/cljev.svg)](https://clojars.org/io.github.joshrotenberg/cljev)
[![Babashka](https://img.shields.io/badge/babashka-1.13.225%2B-green)](https://babashka.org)
[![JVM](https://img.shields.io/badge/JVM-21%20%7C%2025-green)](https://clojure.org)
[![license](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)

A Clojure client for [Jev](https://docs.typesafe.ai/introduction), TypeSafe's
System One model, for Babashka and the JVM from the same source. You send a
`state` and a set of typed questions about it, and get back structured answers
with probabilities.

It covers the two endpoints in the
[TypeSafe OpenAPI spec](https://api.typesafe.ai/openapi.json):
`POST /v1/systemone` and `GET /v1/models`.

cljev is at 0.x: the API may change between minor versions until 1.0. It is
not affiliated with TypeSafe.

## Using cljev

cljev is on [Clojars](https://clojars.org/io.github.joshrotenberg/cljev),
and each release is also a git tag (see [RELEASING.md](RELEASING.md)):

<!-- x-release-please-start-version -->
```clojure
;; deps.edn or bb.edn, from Clojars
{:deps {io.github.joshrotenberg/cljev {:mvn/version "0.1.0"}}}

;; or from the tag; :git/sha is the tag's commit
{:deps {io.github.joshrotenberg/cljev {:git/tag "v0.1.0" :git/sha "..."}}}
```
<!-- x-release-please-end -->

`git ls-remote https://github.com/joshrotenberg/cljev.git refs/tags/<tag>`
gives the sha.

The only runtime dependencies are `babashka.http-client` and `cheshire`, both
of which Babashka bundles.

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

For a single question, `ask-one` returns the answer itself, with the model,
usage, and request id in its metadata:

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

Instructions, option descriptions, and level descriptions can be strings,
maps, or vectors.

The constructors return plain maps, and `ask` accepts hand-written maps in the
same shape as the HTTP API (`{:type :noul :instructions "..."}`).

#### Choice options and their order

`choice` takes its options in three forms:

```clojure
(jev/choice "Tone?" [:angry :calm])                            ; names
(jev/choice "Tone?" [[:angry "Hostile or upset"] [:calm "Neutral"]]) ; [name description] pairs
(jev/choice "Tone?" {:angry "Hostile or upset" :calm "Neutral"})     ; a map
```

Options are sent in the order given. TypeSafe
[documents](https://docs.typesafe.ai/model-jaggedness) that the order can
affect the answer and that Jev can lean toward the first option. A collection
keeps its order for any number of options. A Clojure map literal only keeps
its order up to 8 entries, so use pairs for a larger set with descriptions.

### Acting on an answer

`verdict` reads a yes/no answer as `:yes`, `:no`, or `:unsure`. The threshold,
from 0.5 to 1, is how sure the answer must be either way; the default of 0.5
is never unsure.

```clojure
(case (jev/verdict (jev/ask-one client message (jev/noul "Is this spam?")) 0.8)
  :yes    (discard message)
  :no     (deliver message)
  :unsure (hold-for-review message))
```

`sample` picks a choice option, or a score level, at random in proportion to
its probability, where `:choice` always gives the likeliest one. An option
with probability zero is never picked. Pass a roll from 0 to 1 as a second
argument for a draw you can reproduce.

```clojure
(jev/sample {:type :choice :choice :talk :probabilities {:talk 0.7 :flee 0.3}})
;; => :talk about 70% of the time, :flee about 30%
```

Both are pure functions and make no request.

### Many states

`ask-many` asks the same questions about each of a collection of states, one
request per state, several at a time. Results come back in input order. A
failed request does not stop the others; its result is `{:error e}`.

```clojure
(jev/ask-many client
              ["Where is my refund?" "I love this product."]
              {:question (jev/noul "Is this text a question?")}
              {:concurrency 4})   ; default 8
;; => [{:model .. :answers {:question {:type :noul :noul 0.99}} :usage ..}
;;     {:model .. :answers {:question {:type :noul :noul 0.01}} :usage ..}]
```

### Request options

`ask`, `ask-one`, and `ask-many` take an options map as a last argument:

```clojure
(jev/ask client state questions
         {:model "jev-1.14.0"   ; default is the client's model, jev-latest
          :long-context? true   ; TypeSafe-Danger-Zone-Long-Context: true
          :timeout-ms 30000
          :headers {"x-request-id" "abc"}
          :extra-body {:some_new_field 1}})
```

`jev-latest` is an alias that moves when a release ships. Pass a versioned id
to pin the model, per request or as `:model` on the client.

`:extra-body` adds top-level fields to the request, for API features this
client has no option for.

### Images

`image` takes a path, a file, or a byte array with a format (`:png`, `:jpg`,
`:jpeg`). Put the image wherever a value goes in the state or in a question's
instructions or criteria. Requests with images or box questions need
`:images? true`, which sends the `TypeSafe-Danger-Zone-Images: true` header.

```clojure
(jev/ask client
         {:photo (jev/image "cat.png")}
         {:cat?  (jev/noul "Is there a cat in the photo?")
          :where (jev/box "the cat")}
         {:images? true})
```

To use an image inside a string, write its `:ref` into the string and pass
the image in `:assets`:

```clojure
(let [before (jev/image "before.jpg")
      after  (jev/image "after.jpg")]
  (jev/ask client
           (str "Before: " (:ref before) " After: " (:ref after))
           {:changed (jev/noul "Did anything change between the two photos?")}
           {:assets [before after] :images? true}))
```

`:assets` also takes a map of your own reference strings to images, or to raw
asset maps in the API's shape.

Image bytes are uploaded as `multipart/form-data`. Pass `:upload :base64` to
put them in the JSON body instead, which makes each image about a third
larger. An image used in several places is uploaded once.

### Models

```clojure
(jev/models client)
;; => [{:name "jev-latest" :description "..." :release-date "2026-10-22T02:00:00+00:00"} ...]
```

### Response metadata

Results of `ask` and `models` carry the HTTP exchange in their metadata:

```clojure
(meta (jev/ask client state questions))
;; => {:request-id "req_01a1..."
;;     :response {:status 200 :headers {...} :body {...}}}
```

`:body` is the JSON as received, with string keys, including any fields this
client does not know about. Answers of a type the client does not know are
returned with their fields as they came.

### Client options

```clojure
(jev/client {:api-key "..."        ; default: TYPESAFE_API_KEY
             :base-url "..."       ; default: TYPESAFE_BASE_URL, then https://api.typesafe.ai
             :model "jev-1.14.0"   ; default: TYPESAFE_DEFAULT_MODEL, then jev-latest
             :timeout-ms 10000
             :compression :gzip
             :systemone-path "/v1/systemone"
             :models-path "/v1/models"
             :retry {:max-retries 2
                     :backoff-initial-ms 500
                     :backoff-max-ms 5000
                     :backoff-jitter 0.25
                     :max-retry-after-ms 60000}
             :headers {"x-team" "support"}})
```

The values shown for `:timeout-ms`, `:compression`, the two paths, and
`:retry` are the defaults for the TypeSafe API.

- **API key.** Surrounding whitespace is removed, so a key read from a file
  works. A key with whitespace or non-ASCII characters inside it is rejected
  before any request. The key is stored in the client's metadata, so printing
  the client does not print the key.
- **Compression.** Request bodies of 1 KiB or more are gzip-compressed. Pass
  `:compression nil` to send them as they are. Multipart requests are not
  compressed. For another `:base-url` compression is off by default; see
  [Other System One backends](#other-system-one-backends).

### Other System One backends

The TypeSafe API is the default. Other servers implement the same API, among
them gateways that resell Jev and self-hosted servers that run open models,
and `:base-url` points the client at one:

```clojure
;; a local server: no key, so no authorization header is sent
(jev/client {:base-url "http://localhost:8080" :model "my-local-model"})

;; a gateway with its own key and model name
(jev/client {:base-url "https://openrouter.ai/api"
             :api-key  (System/getenv "OPENROUTER_API_KEY")
             :model    "typesafe/jev-1.13"})
```

What changes for a `:base-url` other than TypeSafe's:

- **No key is required**, and `TYPESAFE_API_KEY` is not used, so the TypeSafe
  key is never sent to another server. Pass that server's key as `:api-key`
  if it needs one.
- **Compression is off** unless you pass `:compression :gzip`.
- **Paths can be changed** with `:systemone-path` (default `/v1/systemone`)
  and `:models-path` (default `/v1/models`).

For any server, the client reads the looser responses some of them send:
answers without a `type` tag take the type of their question, missing fields
such as `usage` are left out of the result, extra fields are kept, a model
list in the OpenAI shape (`data` with `id`) gets a `:name` from each `id`,
and the request id falls back to the `x-request-id` header.

Another server answers with another model. The numbers have the same shape
but not the same meaning, so a threshold tuned against Jev does not carry
over. Features such as images, box questions, and long context depend on the
server.

### Retries and errors

Responses with status 408, 429, or 5xx (including 529) and connection
failures are retried with exponential backoff. `retry-after` and
`retry-after-ms` response headers set the wait when present. A reply that asks
for a wait longer than `:max-retry-after-ms` is thrown without retrying.

Failures are thrown as `ex-info`:

| `:type` in `ex-data` | When | Other keys |
| - | - | - |
| `:cljev.core/api-error` | Non-2xx reply after retries | `:status`, `:detail`, `:body`, `:headers`, `:request-id` |
| `:cljev.core/connection-error` | Server unreachable or timed out after retries | `:attempts`, with the cause attached |
| `:cljev.core/invalid-question` | Bad choice options or score levels | |
| `:cljev.core/invalid-asset` | Unsupported image format, or a bad `:assets` value | |
| `:cljev.core/invalid-request` | `ask` called with no questions | |
| `:cljev.core/invalid-option` | Unknown `:upload` or `:compression` value, or a `verdict` threshold outside 0.5 to 1 | |
| `:cljev.core/invalid-answer` | `verdict` or `sample` given an answer it cannot read | |
| `:cljev.core/missing-api-key` | No API key for the TypeSafe API, given or in the environment | |
| `:cljev.core/invalid-api-key` | API key contains whitespace or non-ASCII characters | |

## Examples

`examples/` has example programs, each a namespace with a `-main`. Run them
from the project root with `TYPESAFE_API_KEY` set:

| Command | What it does |
| - | - |
| `bb -m cljev.example.models` | Lists the models the account can use. A quick check that the key works. |
| `bb -m cljev.example.triage [MESSAGE]` | Asks a noul, a choice, and a score about a support message in one request. |
| `bb -m cljev.example.classify LABEL LABEL ... < lines` | Labels each line of stdin, one request per line, several at a time. Marks low-confidence labels. |
| `bb -m cljev.example.grep DESCRIPTION < lines` | Prints the lines of stdin that fit a description, using one yes/no question per line in a single request. |

```
git log --format=%s -20 | bb -m cljev.example.classify feature fix docs chore
bb -m cljev.example.grep "mentions a deadline" < notes.txt
```

On the JVM, `clojure -M:dev -m cljev.example.triage` runs the same programs.

## Not implemented

- zstd request compression; gzip is used instead
- An async API; use `ask-many`, `future`, or virtual threads to fan out requests

## Development

[AGENTS.md](AGENTS.md) has the layout, the checks, and the conventions.

```
bb test        # tests under Babashka
bb jvm-test    # the same tests on the JVM
bb lint        # clj-kondo, no em dashes, conventional commits
bb fmt:check   # cljfmt check; bb fmt fixes
bb check       # all four
```

`bb test` and `bb jvm-test` use a stubbed request function and a local
http-kit server, and never call the TypeSafe API.

`bb test:live` calls the API. It runs only with `CLJEV_LIVE_TESTS=1` and
`TYPESAFE_API_KEY` set, its requests are billed, and its assertions depend on
the model's answers. It is not part of `bb check` or CI.

## License

MIT
