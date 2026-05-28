# Development Rules

## Conversational Style

- Keep answers short and concise
- No emojis in commits, issues, PR comments, or code
- No fluff or cheerful filler text
- Technical prose only, be kind but direct (e.g., "Thanks @user" not "Thanks so much @user!")
- **Language**: Always reply in the same language the user writes in. If the user writes in Chinese, respond in Chinese.

## Code Quality

- No `@SuppressWarnings` unless absolutely necessary — prefer fixing the underlying warning
- Never add `@SuppressWarnings("lgtm[...]")` to silence static analysis on new code
- Do not downgrade dependencies to fix issues; upgrade them instead
- Always ask before removing functionality or code that appears intentional
- Never hardcode provider-specific logic in shared code (ErrorExplainer, GlobalConfigurationImpl). Provider-specific behaviour belongs in the provider class extending `BaseAIProvider`
- All new code must compile with the `hpi.strictBundledArtifacts` check enabled — do not add undocumented transitive dependencies to `<hpi.bundledArtifacts>` without explicit permission
- `Secret` (not plain `String`) for all credential/API-key fields in provider classes
- All public provider constructors must use `@DataBoundConstructor`

## Commands

```bash
make build         # Compile (skip tests)
make test          # Run all unit tests
make verify        # Full CI check (compile + test + verify)
make package       # Build .hpi (skip tests)
make run           # Start Jenkins locally at http://localhost:8080/jenkins
make debug         # Start Jenkins with remote debugger on port 8000
make lint          # Checkstyle + SpotBugs (report only, non-blocking)
make reinstall     # Clean build and install .hpi locally
```

- Run `make lint` before committing. Fix all warnings unless they are pre-existing and unrelated to your changes.
- Run `make verify` for full CI parity. All tests must pass.
- **NEVER run `make run` or `make debug`** unless the user explicitly asks for it.
- Run specific tests with `mvn test -Dtest=ClassName` or `mvn test -Dtest=ClassName#methodName` from the repo root.

## Test Conventions

- **JUnit 5 only** — all test classes use `org.junit.jupiter.api.Test`. No JUnit 4 imports (`org.junit.Test`, `org.junit.Assert`).
- Use `@WithJenkins` (from `org.jvnet.hudson.test.junit.jupiter.WithJenkins`) on test classes that need a JenkinsRule.
- When testing providers, add validation tests (null/empty API keys, models, URLs) in `ProviderTest.java` alongside the existing provider tests.
- Provider-specific behaviour tests (e.g., assistant creation, custom auth flow) go in `src/test/java/.../provider/<ProviderName>Test.java`.
- WireMock (`com.github.tomakehurst.wiremock`) is used for SCM API integration tests in `autofix/scm/`.
- Use `TestProvider` (in `src/test/.../provider/TestProvider.java`) as a stub when you need a controlled provider that does not call a real API.
- If you create or modify a test file, run it and iterate until it passes.
- Do not commit tests that require real API keys, real network calls, or paid tokens.

## Adding a New AI Provider

- Read the source of an existing provider that is closest to the new one (e.g., `OpenAIProvider.java` for OpenAI-compatible APIs, `BedrockProvider.java` for AWS Bedrock, `CustomOktaAIProvider.java` for OAuth2-based auth)
- Follow the same patterns: extend `BaseAIProvider`, annotate with `@Extension`, use `@DataBoundConstructor`, implement `createAssistant()` / `createFixAssistant()` / `isNotValid()`, and add a `DescriptorImpl` with `@Symbol`
- If a new LangChain4j module is needed, add it to `pom.xml` (following existing exclusion patterns) and to `<hpi.bundledArtifacts>`
- Add null/empty validation tests in `ProviderTest.java`; put provider-specific tests in a separate `<ProviderName>Test.java`
- No changes needed in `GlobalConfigurationImpl` — providers are discovered via `ExtensionPoint`
- Update the README provider list, keeping providers in alphabetical order

## Feature Documentation

Complex features (e.g., AutoFix, usage quotas) must have a corresponding markdown doc under `docs/`. When you add or significantly update such a feature, keep the doc in sync. Existing feature docs:

- `docs/auto-fix.md` — experimental auto-fix feature
- `docs/usage-quota.md` — per-provider/model request quotas

## Branching Rules

- **NEVER push directly to `main`.** All code must enter `main` via pull request (PR) only. Even when working alone, create a feature branch and open a PR — do not commit or push directly to `main`.

## PR Workflow

- Analyze PRs without pulling locally first
- If the user approves: create a feature branch, pull PR, rebase on main, apply adjustments, commit, merge into main, push, close PR, and leave a comment
- **Never open PRs yourself.** Work in feature branches until everything meets requirements, then merge into main and push.
- All PRs target `main` branch

## Git Rules for Parallel Agents

Multiple agents may work on different files in the same worktree simultaneously. You MUST follow these rules:

### Committing

- **ONLY commit files YOU changed in THIS session**
- NEVER use `git add -A` or `git add .` — these sweep up changes from other agents
- ALWAYS use `git add <specific-file-paths>` listing only files you modified
- Before committing, run `git status` and verify you are only staging YOUR files
- Track which files you created/modified/deleted during the session

### Forbidden Git Operations

These commands can destroy other agents' work:

- `git reset --hard` — destroys uncommitted changes
- `git checkout .` — destroys uncommitted changes
- `git clean -fd` — deletes untracked files
- `git stash` — stashes ALL changes including other agents' work
- `git add -A` / `git add .` — stages other agents' uncommitted work
- `git push --force` / `git push -f` — overwrites remote history; agents are NEVER allowed to force push under any circumstances

### Safe Workflow

```bash
# 1. Check status first
git status

# 2. Add ONLY your specific files
git add src/main/java/io/jenkins/plugins/explain_error/provider/NewProvider.java
git add pom.xml

# 3. Commit
git commit -m "feat: add NewProvider integration"

# 4. Push (pull --rebase if needed, but NEVER reset/checkout)
git pull --rebase && git push
```

### If Rebase Conflicts Occur

- Resolve conflicts in YOUR files only
- If conflict is in a file you didn't modify, abort and ask the user
- NEVER force push

### User Override

If the user instructions conflict with rules set out here, ask for confirmation that they want to override the rules. Only then execute their instructions.
