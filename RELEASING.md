# Releasing

Each release is a git tag and a Clojars artifact,
`io.github.joshrotenberg/cljev`. Clojure CLI and Babashka projects can depend
on either (`:mvn/version`, or `:git/tag` with its `:git/sha`).

## How a release happens

1. Merged pull requests land on `main` with conventional-commit subjects.
2. `.github/workflows/release-please.yml` keeps a release pull request
   open, titled `chore(main): release X.Y.Z`, with the next version in
   `version.txt`, the version in `README.md`'s coordinate block, the version
   the client sends in its user-agent header (`cljev.core/version`), and the
   changelog in `CHANGELOG.md`. The first release is `0.1.0`.
3. Merging that pull request tags `vX.Y.Z` on `main` and creates the GitHub
   release.
4. The workflow's `clojars` job then checks out the tag, runs `bb test` and
   `bb jvm-test`, and runs `clojure -T:build deploy` (`build.clj`), which
   builds the jar and its pom and uploads them to Clojars. A failing test
   stops the deploy; the GitHub release and tag already exist by then. CI
   builds the same jar on every pull request.

## Repository settings it needs

- Settings, Actions, General: "Allow GitHub Actions to create and approve
  pull requests", or release-please cannot open its pull request.
- Optionally a `RELEASE_PLEASE_TOKEN` secret: a fine-grained token with
  read and write Contents, Pull requests and Issues on this repository. A
  pull request opened with the workflow token does not start other
  workflows, so without the secret the release pull request's CI does not
  run until it is pushed to again.
- A `CLOJARS_TOKEN` secret: a Clojars deploy token. The group
  `io.github.joshrotenberg` is verified on Clojars by signing in with the
  GitHub account. A `CLOJARS_USERNAME` repository variable names the Clojars
  user the token belongs to (default `joshrotenberg`).

## Depending on a release

```clojure
;; deps.edn or bb.edn
{:deps {io.github.joshrotenberg/cljev {:mvn/version "0.1.0"}}}
;; or
{:deps {io.github.joshrotenberg/cljev {:git/tag "v0.1.0" :git/sha "<the tag's commit, 7 or more characters>"}}}
```

`git ls-remote https://github.com/joshrotenberg/cljev.git refs/tags/v0.1.0`
prints the sha.

## Building the jar locally

```sh
clojure -T:build jar     # target/cljev-<version>.jar and target/classes/META-INF/maven/.../pom.xml
```
