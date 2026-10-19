# Agent Instructions

A Jenkins plugin that explains build failures with AI and can open auto-fix pull requests. Read the code, the README
and `docs/` for how it works. This file only holds what they do not tell you.

## Working with the maintainer

- Reply in the language the user writes in. Be short and direct: no emojis, no filler.
- Ask before removing functionality or code that looks intentional.
- If an instruction conflicts with a rule in this file, ask the user to confirm before following it.

## Commands

- `make build`, `make test`, `make lint`, `make verify` (the full CI check). One test: `mvn test -Dtest=Class#method`.
- Never run `make run` or `make debug` unless asked.
- Before committing, run the tests you touched and `make lint`, and fix what your change introduced.

## Code

- Provider-specific behaviour belongs in the provider class, never in shared code such as `ErrorExplainer` or
  `GlobalConfigurationImpl`. To add a provider, follow `.github/prompts/add-provider.prompt.md`.
- Credentials are `Secret` and are never logged. Configuration endpoints use `@POST` and `checkConfigurePermission`.
  URLs with embedded credentials are rejected, and proxy credentials are sent to the proxy only.
- Fix a warning instead of suppressing it. Upgrade a dependency instead of downgrading it.
- A new LangChain4j module takes its version from `langchain4j.version`, needs the SLF4J and Jackson exclusions and an
  entry in `<hpi.bundledArtifacts>`. Add nothing else there without permission.
- Lines up to 120 characters. Log with `java.util.logging`; per-request notes go at `FINE`.

## Tests

- JUnit 5 with `@WithJenkins`. Keep the `JenkinsRule` parameter even when it is unused: without it Jenkins does not
  start.
- Never call or mock a real AI API; use `FakeAIProvider`. No test may need the network or real credentials.

## Documentation

- A feature with a page under `docs/` keeps that page in sync. `README.md` and `docs/providers.md` list providers in
  alphabetical order.

## Git

- Never commit on or push to `main`, and never force push. Every change reaches `main` through a pull request.
- Do not push or open a pull request unless asked.
- Other agents may share this checkout. Stage only the files you changed, by path; never use `git add -A`,
  `git stash`, `git reset --hard`, `git checkout .` or `git clean`.
- If a rebase conflicts in a file you did not change, stop and ask.
