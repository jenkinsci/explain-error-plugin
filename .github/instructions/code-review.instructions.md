---
applyTo: "**/*.java, **/*.xml, **/*.jelly, **/*.js"
---

# Code review

What Jenkins hosting reviewers and earlier reviews of this plugin have asked for. Flag a change that breaks one of
these; do not comment on code that already follows them.

## Java

- Tracing is logged at `FINE` or lower. `INFO` is for significant events, `WARNING` for something an administrator may
  need to act on. The same message does not go to both the Jenkins log and the build console.
- Secrets are `Secret`, never `String`, and are never logged.
- Web methods that change state or handle credentials require POST and a permission check.
- Public methods state their nullability with `@NonNull` / `@CheckForNull`.
- Configurable classes have one `@DataBoundConstructor` for required fields, `@DataBoundSetter` for the rest, and a
  `@Symbol` so Configuration as Code can name them.
- A renamed or removed persisted field has a `readResolve()` migration and a test for it.
- A build action is attached with `addOrReplaceAction`. UI that must also appear on existing builds comes from a
  `TransientActionFactory`, not a `RunListener`.

## Jelly, JavaScript and CSS

- No inline scripts or event handlers (Content Security Policy); JavaScript lives in its own file.
- Requests use `fetch` with the crumb, and build URLs from the root URL so a context path such as `/jenkins` works.
- Use Jenkins design library components, symbols and CSS variables. Hard-coded colours break the dark theme.
- The browser console is for errors only.

## pom.xml

- Jenkins plugin versions come from the BOM. Common libraries come from API plugins such as `jackson2-api` and
  `commons-lang3-api`, not from bundled copies.
- LangChain4j modules exclude SLF4J and Jackson.
- No `<developers>` section.

## Tests

- AI calls go through `FakeAIProvider`; LangChain4j and HTTP clients are not mocked.
- A behaviour change has tests for success and failure. A configuration change has migration and Configuration as
  Code tests.
