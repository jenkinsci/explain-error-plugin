---
name: review-pull-request
description: Review a Jenkins plugin pull request and give a merge recommendation, covering security, compatibility, dependencies, tests and CI. Use when asked to review a PR by number or URL.
---

# Review a Jenkins plugin pull request

Review the diff against its target branch and say whether it can be merged. Read the pull request with `gh`
(description, diff, checks); do not check it out locally unless asked. If no pull request is given, ask for its
number or URL.

Judge the change against the repository's own conventions (`AGENTS.md`, the existing code and tests), not against a
generic checklist. Look for what can hurt users:

- **Security**: secrets stored or logged in plain text; web methods without a permission check or POST protection;
  user-controlled values rendered unescaped; requests or file paths built from untrusted input.
- **Compatibility**: saved configuration, Configuration as Code and existing Jenkinsfiles keep working; a renamed or
  removed persisted field has a migration; defaults do not change silently.
- **Dependencies**: no downgrades, and nothing bundled that Jenkins or an API plugin already provides.
- **Tests and documentation**: risky behaviour has a test that needs no network or real credentials; a user-visible
  change updates the documentation.
- **CI and mergeability**: failing or pending checks, conflicts.

A blocker is something that can break users, security, compatibility or CI. Everything else is a suggestion. Keep the
two apart, and report only what you can point to in the diff.

## Output

1. **Summary**: what changed and the main risk, in one or two sentences.
2. **Recommendation**: `Ready to merge`, `Wait for CI`, `Request changes` or `Needs more information`. Never
   `Ready to merge` while a check is failing, pending or unknown.
3. **Blocking issues**: each with `file:line`, why it matters and what to change.
4. **Suggestions**: the same, briefly.
