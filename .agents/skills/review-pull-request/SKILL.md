# Skill: Review Pull Request for explain-error-plugin

## Description

Step-by-step workflow for reviewing pull requests submitted to the `jenkinsci/explain-error-plugin` repository. Produces a structured, actionable review covering security, code quality, architecture, UI, testing, and dependency management — consistent with Jenkins hosting requirements.

## Tools

- `read` — read file contents
- `bash` — run shell commands (git, gh CLI, make, grep, find)
- `edit` — apply precise file edits
- `write` — create or overwrite files

---

## Workflow

### Step 1 — Fetch the PR

Load the PR using the PR number or URL provided by the user.

```bash
# Fetch PR metadata (title, description, state, author)
gh pr view <number> --repo jenkinsci/explain-error-plugin --json title,body,state,author,labels,assignees

# List changed files
gh pr diff <number> --repo jenkinsci/explain-error-plugin --name-only

# Get full diff for deep inspection
gh pr diff <number> --repo jenkinsci/explain-error-plugin
```

Collect:
- Title, description, linked issue(s)
- Changed files list
- Diff/patch of each changed file

> If no PR number is given, ask: *"Which PR number or URL would you like me to review?"*

---

### Step 2 — Understand the Intent

Read the PR description and linked issue to understand **what problem is being solved**.

Check:
- Is there a linked issue? Does the change match the issue scope?
- Is the change scoped (minimal, focused) or does it include unrelated refactoring?
- Is there a test included?

Flag if the description is absent or too vague to evaluate intent.

---

### Step 3 — Security Review

For every changed `.java` file, check:

#### API Keys / Secrets
- [ ] API keys use `Secret apiKey` (not `String`)
- [ ] Access uses `Secret.toString(apiKey)` only at usage point
- [ ] No secrets logged or printed anywhere

#### Permission Checks
- [ ] All `doXxx()` form methods that change state or validate credentials use `@RequirePOST` (or `@POST`)
- [ ] `Jenkins.get().checkPermission(Jenkins.ADMINISTER)` present in sensitive operations

#### Nullability
- [ ] Public API methods annotated with `@NonNull` or `@CheckForNull` (from `edu.umd.cs.findbugs.annotations`)
- [ ] No unguarded `.get()` calls on `Optional` or nullable values

#### Input Validation
- [ ] User-supplied inputs validated at system boundary (form fields, pipeline step params)
- [ ] No SQL/XSS/command injection surface (no shell exec with user data, no raw HTML output)

---

### Step 4 — Code Architecture Review

#### Jenkins Extension Points
- [ ] New config classes use `@Extension` + `@Symbol` for CasC support
- [ ] `@DataBoundConstructor` present on constructors; optional fields use `@DataBoundSetter`
- [ ] Global config provides a static `get()` method

#### AI Provider Pattern
- [ ] New providers extend `BaseAIProvider` and implement `ExtensionPoint`
- [ ] Provider descriptor extends `BaseProviderDescriptor` with `@Symbol` annotation
- [ ] `createAssistant()` and `createFixAssistant()` implemented using LangChain4j (no direct HTTP/JSON)
- [ ] `isNotValid()` implemented to guard against empty config

#### Action Management
- [ ] `addOrReplaceAction()` used (not `addAction()`) to avoid duplicate actions
- [ ] New UI actions use `TransientActionFactory` instead of `RunListener`

#### Backward Compatibility
- [ ] If config fields are renamed or removed, a `readResolve()` migration method is present
- [ ] Old fields marked `@Deprecated` and `transient` if replaced

#### Logging
- [ ] `java.util.logging.Logger` used (not SLF4J directly, not `System.out.println`)
- [ ] Trace/debug calls use `FINE`/`FINER`/`FINEST`
- [ ] `INFO` only for significant operational events
- [ ] No `e.printStackTrace()` — exceptions logged via `LOGGER.log(Level.WARNING, "...", e)`

#### Error Handling
- [ ] Failures throw `ExplanationException` (not returned as strings)
- [ ] Exception messages are user-friendly and actionable

#### Feature Documentation
- [ ] If PR modifies AutoFix or usage quota logic, corresponding doc under `docs/` is updated (`docs/auto-fix.md`, `docs/usage-quota.md`)
- [ ] New complex features have a matching `docs/<feature>.md`

---

### Step 5 — UI / Jelly Review

For `.jelly` and `.js` files:

#### Design System
- [ ] Uses Jenkins design library components (`l:card`, `jenkins-button`, `jenkins-!-*` spacing classes)
- [ ] No hard-coded colors — uses CSS variables (`var(--background)`, `var(--text-color)`)
- [ ] Icons use symbol library (`symbol-help`, etc.) not image paths

#### CSP Compliance
- [ ] No inline `<script>` blocks in Jelly — JavaScript is in external `.js` files
- [ ] No `onclick="..."` inline handlers

#### JavaScript
- [ ] Uses `fetch()` API (not `XMLHttpRequest`)
- [ ] CSRF crumb included in POST requests via `crumb.wrap({...})`
- [ ] Root context handled via `document.querySelector('head').getAttribute('data-rooturl')`

---

### Step 6 — Configuration as Code (CasC) Review

- [ ] All new `GlobalConfiguration` subclasses have `@Symbol`
- [ ] All new provider descriptors have `@Symbol`
- [ ] If a new config field is added, verify it round-trips through a CasC YAML test

---

### Step 7 — Dependency Management Review

For `pom.xml` changes:

- [ ] No version specified for BOM-managed Jenkins plugin dependencies
- [ ] Common libraries use API plugins (`jackson2-api`, `commons-lang3-api`) — not bundled JARs
- [ ] `commons-lang` (old) never added — only `commons-lang3-api`
- [ ] New third-party libraries (e.g., LangChain4j providers) exclude `slf4j-api` and `jackson-databind`:
  ```xml
  <exclusions>
    <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></exclusion>
    <exclusion><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></exclusion>
  </exclusions>
  ```

---

### Step 8 — Testing Review

- [ ] Tests use JUnit 5 (`@Test`, `@WithJenkins`)
- [ ] **No mocking of LangChain4j internals** — `TestProvider` (extends `OpenAIProvider`) used instead
- [ ] Both success path and failure path tested
- [ ] New config fields have a CasC round-trip test
- [ ] New pipeline step parameters have an `ExplainErrorStepTest` entry
- [ ] Migration logic (if any) has a `ConfigMigrationTest` entry

---

### Step 9 — Build Verification

Confirm CI passes on the PR. Do NOT pull and run locally unless the user explicitly asks.

CI must show:
- Compile: `make build` passes (no compile errors)
- Tests: `make test` passes (all tests green)
- Lint: `make lint` passes (no new Checkstyle/SpotBugs warnings)
- Full CI: `make verify` passes

Flag any CI failures as blocking. Flag test-only failures as high priority.

---

### Step 10 — Compose the Review

Structure the output as:

```
## PR Review: #<number> — <title>

### Summary
<1–2 sentence summary of what the PR does and whether the intent is clear>

### Overall Verdict
✅ Approve | ⚠️ Request Changes | ❌ Block

### Blocking Issues
<List issues that MUST be fixed before merge, e.g. security, missing tests>

### Non-Blocking Suggestions
<Optional improvements: naming, Javadoc, minor style>

### Checklist Results
| Area              | Status |
|-------------------|--------|
| Security          | ✅ / ⚠️ / ❌ |
| Architecture      | ✅ / ⚠️ / ❌ |
| UI / Jelly        | ✅ / ⚠️ / ❌ |
| CasC Support      | ✅ / ⚠️ / ❌ |
| Dependencies      | ✅ / ⚠️ / ❌ |
| Tests             | ✅ / ⚠️ / ❌ |
| Feature Docs      | ✅ / ⚠️ / ❌ |
| Build             | ✅ / ⚠️ / ❌ |
```

---

## Decision Tree

```
PR opened
│
├─ Description missing or vague?
│   └─ Request description before reviewing
│
├─ New AI provider added?
│   ├─ Extends BaseAIProvider? → Check createAssistant(), createFixAssistant(), isNotValid(), @Symbol
│   ├─ Has Jelly config.jelly? → Check design library usage
│   ├─ Has pom.xml dependency? → Check exclusions
│   └─ Has TestProvider-based tests? → Check success + failure cases
│
├─ Config field added or renamed?
│   ├─ readResolve() migration present? → Must be for renamed/removed fields
│   └─ CasC round-trip test present? → Required
│
├─ Pipeline step parameter added?
│   ├─ @DataBoundSetter used? → Required for optional params
│   └─ ExplainErrorStepTest covers it? → Required
│
├─ Modifies AutoFix or usage quota logic?
│   └─ Corresponding doc under docs/ updated? → Required
│
└─ UI change (Jelly / JS)?
    ├─ Inline script? → Block: must be extracted
    ├─ Hard-coded colors? → Block: use CSS vars
    └─ fetch() used? → Crumb required for POST
```

---

## Quality Completion Criteria

A PR is ready to merge when:
1. All **Blocking Issues** are resolved
2. CI (compile + tests) is green
3. No secrets stored as `String`
4. No `@Symbol` missing on new extension points
5. At least one test covers the new/changed behavior
6. UI follows Jenkins design library (if applicable)
7. Feature docs under `docs/` are updated when relevant logic changes

---

## Example Prompts

- *"Review PR #42 in jenkinsci/explain-error-plugin"*
- *"Do a security-focused review of PR #15"*
- *"Check if PR #7 follows the testing requirements for this plugin"*
- *"Review the dependency changes in PR #33"*
