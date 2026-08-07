# Agent Instructions

## Project Overview

The Explain Error Plugin is a Jenkins plugin that provides AI-powered explanations for build failures and pipeline errors. It integrates with twelve AI providers (see the README for the full list) to analyze error logs and provide human-readable insights, and can optionally open auto-fix pull requests.

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
- Indentation 4 spaces, max line length 120 characters
- Logging via `java.util.logging.Logger`; per-request configuration notes log at `FINE`
- `@NonNull` / `@CheckForNull` (from `edu.umd.cs.findbugs.annotations`) to document nullability
- Jenkins patterns: `@Extension` for extension points, `@Symbol` for CasC, `@POST` plus permission checks for security-sensitive form/AJAX endpoints

## Architecture

### Key Components

- **GlobalConfigurationImpl**: Main plugin configuration class with `@Symbol("explainError")` for Configuration as Code support, handles migration from legacy enum-based configuration
- **BaseAIProvider**: Abstract base class for AI provider implementations with nested `Assistant` interface and `BaseProviderDescriptor` for extensibility; the primary factories carry the full request context (`item`, `authentication`, `temperature`) and the narrower overloads are final
- **ChatModelAIProvider**: Intermediate base for LangChain4j-backed providers — subclasses implement a single `createChatModel(item, authentication, temperature)` and inherit the assistant plumbing
- **ConnectionDiagnostics**: Layer-by-layer connectivity report (proxy decision, DNS, TCP, HTTP probe) attached to failed "Test Configuration" responses via `BaseProviderDescriptor.testConfigurationFailed`
- **ExplainErrorStep**: Pipeline step implementation for `explainError()` (supports `logPattern`, `maxLines`, `language`, `customContext`, `collectDownstreamLogs`, `downstreamJobPattern`, and all `autoFix*` parameters)
- **ExplainErrorFolderProperty**: Folder-level AI provider override — walks up the folder hierarchy; step > folder > global resolution order
- **ConsoleExplainErrorAction** / **ConsoleExplainErrorActionFactory** / **ConsolePageDecorator**: "Explain Error" button on console output — AJAX action, dynamic injection into runs, and visibility logic
- **ErrorExplanationAction**: Build action for storing and displaying AI explanations
- **ErrorExplainer**: Core error analysis logic that coordinates AI providers and log parsing; resolves provider priority (step > folder > global)
- **PipelineLogExtractor**: Extracts logs from the specific failing Pipeline step node (via `FlowGraphWalker`); integrates with optional `pipeline-graph-view` plugin for deep-linking
- **AutoFixOrchestrator** / **AutoFixAction** / **FixAssistant** / **UnifiedDiffApplier**: AI auto-fix flow — structured fix suggestion → diff validation (±3-line fuzzy matching) → branch → commits → pull request, with rollback on failure
- **ScmApiClient + GitHubApiClient / GitLabApiClient / BitbucketApiClient**: SCM REST clients using JDK `HttpClient` (zero extra dependencies); support GitHub Enterprise, GitLab self-managed, and Bitbucket Cloud
- **JenkinsLogAnalysis**: Structured record for AI responses (errorSummary, resolutionSteps, bestPractices, errorSignature)
- **ExplanationException**: Custom exception for error explanation failures
- **AIProvider**: Deprecated enum for backward compatibility with old configuration format

### Package Structure

```
src/main/java/io/jenkins/plugins/explain_error/
├── GlobalConfigurationImpl.java            # Plugin configuration & CasC + migration logic
├── ExplainErrorStep.java                   # Pipeline step (logPattern, maxLines, language, customContext, autoFix*)
├── ExplainErrorFolderProperty.java         # Folder-level AI provider override
├── ErrorExplainer.java                     # Core error analysis logic (provider resolution)
├── PipelineLogExtractor.java               # Failing step log extraction + pipeline-graph-view URL
├── ConsoleExplainErrorAction*.java         # Console button: action, factory, page decorator
├── ErrorExplanationAction.java             # Build action for results storage/display
├── QuotaEnforcer.java / Usage*.java        # Request quotas and usage metrics
├── JenkinsLogAnalysis.java                 # Structured AI response record
├── AIProvider.java                         # @Deprecated enum (backward compatibility)
├── provider/
│   ├── BaseAIProvider.java                 # Abstract AI service with Assistant interface
│   ├── ChatModelAIProvider.java            # Base for LangChain4j ChatModel providers
│   ├── ConnectionDiagnostics.java          # Test Configuration failure diagnostics
│   ├── ProxyAwareHttpClient.java           # Preemptive Proxy-Authorization for langchain4j
│   └── <Name>Provider.java                 # One class per provider (12; see the directory)
└── autofix/
    ├── AutoFixOrchestrator.java            # AI suggestion → branch → commits → PR (with rollback)
    ├── FixAssistant.java / FixSuggestion.java / AutoFix*.java
    ├── UnifiedDiffApplier.java             # Applies unified diffs with ±3-line fuzzy matching
    └── scm/                                # ScmApiClient + GitHub/GitLab/Bitbucket REST clients
```

### AI Service Integration

- All AI services extend `BaseAIProvider` and implement `ExtensionPoint`; providers are discovered automatically — no registration in `GlobalConfigurationImpl`
- LangChain4j-backed providers extend `ChatModelAIProvider` and implement `createChatModel(item, authentication, temperature)`; direct-HTTP providers (Azure OpenAI, Custom Okta, LangGraph) implement the full-context `createAssistant`/`createFixAssistant` instead
- Structured output parsing uses the `JenkinsLogAnalysis` record with `@Description` annotations
- Provider descriptors extend `BaseProviderDescriptor` with `@Symbol` annotations for CasC; `doTestConfiguration` delegates to `runConfigurationTest(context, provider)`
- Graceful error handling with `ExplanationException` and user-friendly messages
- LangChain4j-backed providers do no direct HTTP/JSON handling — the library abstracts API communication; always route through `newLangChainHttpClientBuilder()` for Jenkins proxy support

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

## Build & Dependencies

- Jenkins baseline and Java requirement: see `<jenkins.baseline>` in `pom.xml` (currently 2.528.x, Java 17+)
- LangChain4j version is managed via the `langchain4j.version` property — never hardcode it elsewhere
- New LangChain4j modules need SLF4J and Jackson exclusions (follow existing patterns) and an entry in `<hpi.bundledArtifacts>`
- Key Jenkins dependencies: `jackson2-api`, `workflow-step-api`, `commons-lang3-api`
- Test dependencies: `workflow-cps`, `workflow-job`, `workflow-durable-task-step`, `workflow-basic-steps`, `test-harness`, `wiremock-standalone`

## Test Conventions

- **JUnit 5 only** — all test classes use `org.junit.jupiter.api.Test`. No JUnit 4 imports (`org.junit.Test`, `org.junit.Assert`).
- Use `@WithJenkins` (from `org.jvnet.hudson.test.junit.jupiter.WithJenkins`) on test classes that need a JenkinsRule. Tests must declare the `JenkinsRule` parameter even when unused — the extension boots Jenkins from `resolveParameter`, so removing the parameter silently skips Jenkins startup.
- **Never mock AI APIs directly** — use `FakeAIProvider` to avoid real network calls.
- When testing providers, add validation tests (null/empty API keys, models, URLs) in `ProviderTest.java` alongside the existing provider tests.
- Provider-specific behaviour tests (e.g., custom auth flow, endpoint handling) go in `src/test/java/.../provider/<ProviderName>Test.java`; prefer end-to-end tests against a local `com.sun.net.httpserver.HttpServer` (see `OpenAICompatibleProviderTest`).
- WireMock (`com.github.tomakehurst.wiremock`) is used for SCM API integration tests in `autofix/scm/`.
- If you create or modify a test file, run it and iterate until it passes.
- Do not commit tests that require real API keys, real network calls, or paid tokens.

### FakeAIProvider Pattern

All tests that exercise AI-integrated code use `FakeAIProvider` — a subclass of `OpenAIProvider` that overrides the full-context `createAssistant(item, authentication, temperature)` with a controllable in-memory implementation:

```java
// src/test/java/io/jenkins/plugins/explain_error/provider/FakeAIProvider.java
public class FakeAIProvider extends OpenAIProvider {
    private boolean throwError = false;
    private JenkinsLogAnalysis answer = new JenkinsLogAnalysis("Request was successful", null, null, null);
    private String lastCustomContext;

    @DataBoundConstructor
    public FakeAIProvider() {
        super("https://localhost:1234", "test-model", Secret.fromString("test-api-key"));
    }

    @Override
    public Assistant createAssistant(@CheckForNull Item item, @CheckForNull Authentication authentication,
                                     @CheckForNull Double temperature) {
        return (errorLogs, language, customContext) -> {
            if (throwError) throw new RuntimeException("Request failed.");
            lastCustomContext = customContext;
            return answer;
        };
    }
}
```

Use `provider.setThrowError(true)` to simulate failures, `provider.getLastCustomContext()` (and the other `getLast*` accessors) to assert what was passed to the AI.

## Adding a New AI Provider

- Read the source of an existing provider that is closest to the new one (e.g., `OpenAIProvider.java` for OpenAI-compatible APIs, `BedrockProvider.java` for AWS Bedrock, `CustomOktaAIProvider.java` for OAuth2-based auth)
- Follow the same patterns: LangChain4j-backed providers extend `ChatModelAIProvider` and implement a single `createChatModel(item, authentication, temperature)`; providers that call their API directly extend `BaseAIProvider` and implement the full-context `createAssistant(item, authentication, temperature)` / `createFixAssistant(item, authentication)` (the narrower overloads are final — parameters cannot be dropped by overriding the wrong variant). Implement `isNotValid()`, annotate with `@Extension`, use `@DataBoundConstructor`, and add a `DescriptorImpl` with `@Symbol` whose `doTestConfiguration` is a one-liner delegating to `runConfigurationTest(context, provider)`
- If a new LangChain4j module is needed, add it to `pom.xml` (following existing exclusion patterns) and to `<hpi.bundledArtifacts>`
- Add null/empty validation tests in `ProviderTest.java`; put provider-specific tests in a separate `<ProviderName>Test.java`
- No changes needed in `GlobalConfigurationImpl` — providers are discovered via `ExtensionPoint`
- Update the README provider list, keeping providers in alphabetical order
- Keep the "Architecture" section of this file and `.github/prompts/add-provider.prompt.md` in sync when the provider contract changes

## Feature Documentation

Complex features (e.g., AutoFix, usage quotas) must have a corresponding markdown doc under `docs/`. When you add or significantly update such a feature, keep the doc in sync. Existing feature docs:

- `docs/auto-fix.md` — experimental auto-fix feature
- `docs/usage-quota.md` — per-provider/model request quotas
- `docs/data-protection.md` — log sanitization (when merged)

## Security Considerations

- API keys stored using the Jenkins `Secret` class; never logged
- Configuration changes require ADMINISTER (global) or Item.CONFIGURE (folder/item) permission — use `checkConfigurePermission`
- Validate all user-provided input; reject credentials embedded in URLs
- Proxy credentials must only be sent to the proxy, never to origin servers (see `ProxyAwareHttpClient` and `ConnectionDiagnostics`)

## Branching Rules

- **NEVER push directly to `main`.** This is a hard rule with zero exceptions. All code — no matter how trivial — must enter `main` via pull request (PR) only. Even when working alone, always create a feature branch, push that branch, open a PR, get it reviewed (or self-review), then merge via the GitHub UI. Direct `git push origin main` or commits made while on `main` are strictly forbidden.

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
- `git push origin main` / `git push` (while on `main`) — pushes commits directly to the `main` branch, bypassing the required PR flow; NEVER do this
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
