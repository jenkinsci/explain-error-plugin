---
name: review-pull-request
description: Review Jenkins plugin pull requests for common merge risks, including security, Jenkins extension patterns, compatibility, UI/CSP, dependencies, tests, documentation, and CI status. Use for general PR review without hardcoding project-specific files or feature rules.
---

# Skill: Review Jenkins Plugin Pull Request

## Purpose

Review a Jenkins plugin pull request and produce an actionable merge recommendation. Focus on common problems that apply across Jenkins plugins. Do not assume project-specific file names, feature names, provider classes, or test classes unless they are present in the PR or repository.

## Principles

- Review the PR as a diff against its target branch.
- Prefer evidence from changed files, PR metadata, CI, and existing repository conventions.
- Treat repository-local rules as context, not universal Jenkins requirements.
- Do not request changes for a missing project-specific file unless the PR modified behavior that the repository already documents or tests that way.
- Distinguish blockers from suggestions. A blocker must be something that can break users, security, compatibility, CI, or plugin hosting expectations.
- If the PR cannot be reviewed with available context, state exactly what is missing.

## Workflow

### 1. Identify the PR

If the user gives a PR number or URL, use it. If no PR is provided, ask for the PR number or URL.

Determine the repository from the PR URL when available. Otherwise use the current git remote:

```bash
git remote -v
gh repo view --json nameWithOwner
```

Read PR metadata and changed files:

```bash
gh pr view <number-or-url> --json title,body,state,author,baseRefName,headRefName,isDraft,mergeable,reviewDecision,statusCheckRollup
gh pr diff <number-or-url> --name-only
gh pr diff <number-or-url>
```

Do not pull the PR locally unless the user asks or local inspection is necessary and explicitly approved by the current workflow.

### 2. Understand Intent

Check:

- What user-visible or maintainer-visible behavior changes?
- Is the scope coherent, or are unrelated refactors mixed in?
- Does the PR description explain the motivation and risk?
- Are linked issues, docs, or tests consistent with the implementation?

Flag unclear intent as a review risk, not automatically as a merge blocker.

### 3. Security And Permissions

Look for common Jenkins plugin security issues:

- Secrets, tokens, passwords, and credentials stored as plain `String` instead of Jenkins `Secret` or Credentials API types.
- Secrets logged, exposed in exceptions, serialized into build logs, or returned to UI/API callers.
- Stapler form validation or action methods missing appropriate permission checks.
- State-changing web methods missing POST protection such as `@RequirePOST` where applicable.
- Unsafe HTML rendering, raw user-controlled markup, missing escaping, or new XSS surface.
- SSRF, path traversal, command execution, unsafe archive extraction, or network calls using untrusted input.
- Missing crumb handling for JavaScript POST requests.

Treat security issues as blockers when exploitable or likely exploitable.

### 4. Jenkins Plugin Patterns

Review changed Jenkins extension code for common correctness:

- `@DataBoundConstructor` is used for required constructor-bound fields; optional fields use `@DataBoundSetter`.
- Descriptors and extension points have appropriate `@Extension` and, when user-facing or CasC-relevant, `@Symbol`.
- New `GlobalConfiguration`, `Descriptor`, `Builder`, `Publisher`, `Step`, `Action`, or `Property` implementations follow existing repository patterns.
- New actions avoid duplicate attachment where repeated execution is possible.
- Public model fields and getters preserve compatibility for persisted configuration.
- Renamed or removed persisted fields include migration logic such as `readResolve()` when needed.
- Nullability is handled defensively at API and persistence boundaries.

Do not enforce a specific class name or architecture unless the repository already uses that pattern.

### 5. Pipeline, Configuration, And Compatibility

For Pipeline or configuration changes:

- Pipeline step parameter changes preserve existing Jenkinsfiles where possible.
- New optional Pipeline parameters are optional in practice and covered by tests or examples.
- Configuration changes preserve existing saved configuration or provide migration.
- CasC-visible changes are documented or tested when the plugin already supports CasC.
- Defaults are stable and do not silently change behavior for existing installations.

Compatibility regressions are blockers when existing configurations or Pipelines would fail unexpectedly.

### 6. UI, Jelly, And JavaScript

For UI changes:

- Jelly escapes user-controlled values and avoids inline JavaScript handlers.
- JavaScript works under Jenkins context paths and includes crumbs for POST requests.
- UI follows Jenkins styling conventions used by the repository.
- New text is clear, translatable where the surrounding code uses localization, and does not expose internals.
- Forms bind to descriptors or databound models correctly.

Only require design-library-specific components when the repository already uses them or the change is in a modernized UI area.

### 7. Dependencies And Build Metadata

For `pom.xml`, BOM, or plugin metadata changes:

- New dependencies are necessary and not duplicating APIs already provided by Jenkins or API plugins.
- Versions follow repository and Jenkins plugin BOM conventions.
- Bundled dependencies are intentional, minimized, and compatible with Jenkins plugin hosting checks.
- Dependency changes do not downgrade libraries to avoid failures.
- Optional dependencies, plugin dependencies, and minimum Jenkins version changes are justified.
- New transitive dependency risks are considered, especially logging, JSON, HTTP clients, and old libraries.

Treat unnecessary bundled dependencies, version downgrades, and hosting-check failures as blockers.

### 8. Tests And Documentation

Evaluate whether the tests match the risk:

- Behavior changes should have focused tests for success and important failure paths.
- Security, permission, migration, Pipeline, and configuration changes need direct coverage when feasible.
- Tests should not require real credentials, paid services, unstable timing, or external network access.
- Test style should match the repository's existing JUnit and Jenkins test harness conventions.
- User-visible behavior, setup, or operational changes should update README, help files, or docs when the repository has corresponding documentation.

Missing tests are blockers when the changed behavior is risky, security-sensitive, persistence-sensitive, or likely to regress.

### 9. CI And Mergeability

Check PR checks and merge state:

```bash
gh pr checks <number-or-url>
gh pr view <number-or-url> --json mergeable,reviewDecision,statusCheckRollup
```

Report:

- Failing required checks.
- Pending checks that block a confident merge decision.
- Merge conflicts or unknown mergeability.
- Required review state if available.

Do not claim a PR is ready to merge while required CI is failing, pending, or unknown.

### 10. Review Output

Use this structure:

```markdown
## PR Review: #<number> - <title>

### Summary
<1-2 sentences describing what changed and the main risk level.>

### Merge Recommendation
<Ready to merge | Wait for CI | Request changes | Needs more information>

### Blocking Issues
- <file:line> <problem, impact, and required fix>

### Non-Blocking Issues
- <file:line> <suggestion or maintainability concern>

### Verification
| Area | Result |
| --- | --- |
| Security | Pass / Concern / Not applicable |
| Jenkins compatibility | Pass / Concern / Not applicable |
| UI/CSP | Pass / Concern / Not applicable |
| Dependencies | Pass / Concern / Not applicable |
| Tests | Pass / Concern / Not applicable |
| Documentation | Pass / Concern / Not applicable |
| CI | Pass / Failing / Pending / Unknown |

### Notes
<Assumptions, missing context, or follow-up checks.>
```

Keep findings concise and actionable. For each issue, include why it matters and what the PR author should change.

## Merge Recommendation Rules

- `Ready to merge`: no blockers, required CI is green, and review state is acceptable.
- `Wait for CI`: code looks acceptable, but required checks are pending or unavailable.
- `Request changes`: at least one blocker exists.
- `Needs more information`: intent, diff, or required context is missing enough that review would be speculative.

## Common Blockers

- Exploitable security issue.
- Required CI failure or merge conflict.
- Persisted configuration or Pipeline compatibility regression.
- Missing permission or POST protection on sensitive Jenkins web endpoints.
- Secret exposure or unsafe credential storage.
- Dependency change likely to fail Jenkins plugin hosting checks.
- Risky behavior change without adequate tests.

## Example Prompts

- "Review PR #42."
- "Review https://github.com/jenkinsci/example-plugin/pull/42."
- "Do a security-focused Jenkins plugin review of this PR."
- "Check whether this dependency PR is safe to merge."
