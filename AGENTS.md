# Working on cljev

For agents and contributors working here. Keep it current as the project
changes.

cljev is a Clojure and Babashka client for the TypeSafe System One API and its
Jev models. Read [README.md](README.md) for the library.

## Layout

| Path | Contents |
|---|---|
| `deps.edn` | Clojure dependencies and aliases |
| `bb.edn` | Babashka tasks |
| `.github/` | GitHub workflows |
| `src/` | Source: `cljev.core` is the whole public API |
| `test/` | Tests, run under Babashka and the JVM (`test/cljev/test_runner.clj` lists the namespaces). They never call the TypeSafe API |
| `test-live/` | Opt-in tests that call the TypeSafe API (`bb test:live`) |
| `examples/` | Example programs, each a namespace with a `-main` (`bb -m cljev.example.triage`) |
| `dev/` | `lint.clj` (`bb lint`) |
| `build.clj` | The jar and Clojars deploy (`clojure -T:build jar`) |

## Checks

Run these before pushing; each must pass. `bb check` runs all four:

- `bb fmt:check` (cljfmt; `bb fmt` fixes)
- `bb lint` (clj-kondo and the conventions below)
- `bb test`
- `bb jvm-test`

CI runs those and the jar build.

`bb test:live` is not one of the checks. It calls the TypeSafe API, its
requests are billed, and its assertions depend on the model's answers. Only
the exact environment value `CLJEV_LIVE_TESTS=1`, with `TYPESAFE_API_KEY` set,
enables it; without the flag it skips without touching the network. Run it
after changing how requests are built or sent.

## Conventions

`bb lint` enforces these:

- clj-kondo finds no errors or warnings in `src`, `test`, `test-live`,
  `examples`, `dev` and the build files. `bb lint` runs it through its
  Babashka pod, so there is nothing to install. `.clj-kondo/config.edn` holds
  the configuration.
- Commit subjects in `origin/main..HEAD` use conventional-commit prefixes: `feat`,
  `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`,
  `revert`, with an optional scope and `!` (git's own `Revert "..."` subject
  also passes). Merge commits are not checked.
- Commit messages have no Co-Authored-By, Assisted-By or Generated-By
  trailers and no "Generated with" lines. `bb lint` allows a Co-authored-by
  trailer for a GitHub bot account, which GitHub adds when a bot's pull
  request (release-please's) is squash-merged.
- Tracked `*.md` `*.clj` `*.edn` `*.json` files have no em dashes.

## Design rules

These are not checked by `bb lint`; reviews hold changes to them.

- Every namespace loads under Babashka and on the JVM from the same source.
  Runtime dependencies are limited to what Babashka bundles:
  `babashka.http-client` and `cheshire`.
- Plain data in and out. Questions, answers and the client are maps; answers
  are keyed by the caller's own question and option names.
- The wire format follows the [TypeSafe OpenAPI spec](https://api.typesafe.ai/openapi.json).
  Unknown response fields and answer types pass through instead of failing.
- Choice options keep the order the caller gave. TypeSafe documents that the
  order can affect the answer.
- Tests must not call the TypeSafe API. Live tests are opt-in.
- Nothing in the default test suite or CI may need an API key.
