# PR: Structured output and AI response parsing fix

## Summary

### Improvements

| # | Change |
|---|---|
| I1 | **`returnStructured` parameter** — new optional `explainError` step parameter that returns a `Map<String, Object>` instead of a plain `String`, so pipelines can branch on the structured analysis data. Exposed in **Pipeline Syntax → Snippet Generator** via a new checkbox entry in `ExplainErrorStep/config.jelly`. |
| I2 | **Structured fields exposed over REST** — `errorSummary`, `resolutionSteps`, `bestPractices` and `errorSignature` are annotated `@Exported` on `ErrorExplanationAction` and available at `<build>/api/json`. |

### Fixes

| # | Change |
|---|---|
| F1 | **Markdown code fence parsing** — AI providers using LangChain4j (Anthropic/Claude, OpenAI, Bedrock, etc.) sometimes return JSON wrapped in code fences (` ```json ... ``` `), causing `Failed to parse ... into JenkinsLogAnalysis` at runtime. The `Assistant` interface now returns `String` and `BaseAIProvider` parses the raw text with fence-stripping fallback logic. |
| F2 | **XStream serialization** — `BaseAIProvider.jenkinsLogAnalysis` (`ThreadLocal`) annotated `@XStreamOmitField` so Jenkins can persist providers in folder properties without hitting the XStream security blacklist for `ThreadLocal`. |
| F3 | **`AutoFixOrchestratorTest` compilation** — replaced `when(run.getParent()).thenReturn((Job) job)` with `doReturn(job).when(run).getParent()` to resolve Mockito's inability to infer `OngoingStubbing<capture#N of ?>` for wildcard return types. |
| F4 | **`BitbucketServerApiClientTest` flakiness** — replaced static `configureFor()` / `stubFor()` / `verify()` with `server.stubFor()` / `server.verify()`. The static WireMock admin client pools TCP connections across tests; restarting a server on a different dynamic port left a stale connection, causing `Connection reset`. |

---

# Improvements

## I1 — `returnStructured` parameter

### Usage

```groovy
def result = explainError(
    returnStructured: true,
    logs: currentBuild.rawBuild.getLog(200)
)

echo "Summary: ${result.errorSummary}"
echo "Signature: ${result.errorSignature}"
result.resolutionSteps.each { step -> echo "  - ${step}" }
result.bestPractices.each { tip -> echo "  - ${tip}" }
```

### Map keys

| Key | Type | Description |
|---|---|---|
| `errorSummary` | `String` | One-sentence description of the root cause |
| `resolutionSteps` | `List<String>` | Ordered steps to resolve the error |
| `bestPractices` | `List<String>` | General recommendations to avoid recurrence |
| `errorSignature` | `String` | Short identifier for this class of error |

### Behaviour

- Default is `false`. Existing pipelines are unaffected.
- When `returnStructured: false` (default), the step returns the same `String` as before.
- When `returnStructured: true` and parsing fails (e.g., provider returns non-JSON), the step falls back to returning the raw `String` to avoid breaking the build.

### Implementation — `ExplainErrorStep`

- New field: `returnStructured` (`boolean`, default `false`).
- Execution class changed from `SynchronousNonBlockingStepExecution<String>` to `SynchronousNonBlockingStepExecution<Object>`.
- Returns `Map<String, Object>` when `returnStructured=true`, falls back to `String` otherwise.
- Added a `returnStructured` checkbox entry to `ExplainErrorStep/config.jelly`, the form file Jenkins uses to render the step UI in **Pipeline Syntax → Snippet Generator**.

## I2 — Structured fields exposed over REST

### `ErrorExplainer`

- Added `private JenkinsLogAnalysis jenkinsLogAnalysis` field and `getJenkinsLogAnalysis()` getter.
- After each `provider.explainError()` call: `this.jenkinsLogAnalysis = provider.getJenkinsLogAnalysis()`.
- Both `explainError()` and `explainErrorText()` call `action.setStructuredData(jenkinsLogAnalysis)` immediately after creating the action, so auto-explained builds and console-action explanations also carry structured data.

### `ErrorExplanationAction`

- Added non-final fields: `errorSummary`, `resolutionSteps`, `bestPractices`, `errorSignature`.
- Added `setStructuredData(JenkinsLogAnalysis)` to populate those fields.
- All new fields annotated with `@Exported` for REST API exposure.

---

# Fixes

## F1 — Markdown code fence parsing

### Problem

Several AI providers (most notably Anthropic/Claude) wrap their JSON output in markdown code fences even when prompted for raw JSON:

```
```json
{
  "errorSummary": "...",
  ...
}
```
```

LangChain4j's automatic deserialization into `JenkinsLogAnalysis` does not strip these fences, causing a runtime parse error on every response from these providers.

### Fix — `BaseAIProvider`

- `Assistant.analyzeLogs()` now returns `String` (raw AI response text).
- New private methods: `parseAnalysis(String)`, `tryParseJson(String)`, `toStringList(com.fasterxml.jackson.databind.JsonNode)`.
- `parseAnalysis` tries direct JSON parsing first, then falls back to `tryParseJson`, which strips leading ` ```json ` / ` ``` ` fences before parsing.
- `explainError()` calls `parseAnalysis()` after each AI call and stores the result in `ThreadLocal<JenkinsLogAnalysis> jenkinsLogAnalysis`; clears it on exception.

### Fix — providers

- `AzureOpenAIProvider`, `CustomOktaAIProvider`, `LangGraphProvider` and `TestProvider` updated to return `String` from their assistant lambdas.

## F2 — XStream serialization

`BaseAIProvider.jenkinsLogAnalysis` annotated `@XStreamOmitField` so Jenkins can serialize providers stored in folder properties without XStream raising a security exception for `ThreadLocal`.

## F3 — `AutoFixOrchestratorTest` compilation

Use `doReturn(job).when(run).getParent()` instead of `when(run.getParent()).thenReturn((Job) job)`.

## F4 — `BitbucketServerApiClientTest` flakiness

All `stubFor()` / `verify()` calls migrated to `server.stubFor()` / `server.verify()` instance calls.

---

## Tests

| Class | Tests added | Coverage |
|---|---|---|
| `BaseAIProviderParsingTest` (new) | 8 | `parseAnalysis`, `tryParseJson` — valid JSON, fenced JSON, plain text, null fields, empty lists |
| `ErrorExplanationActionTest` | 3 | `setStructuredData`, `@Exported` REST fields |
| `ExplainErrorStepTest` | 8 | `returnStructured` map keys/types, defaults, fallback to String |

All tests pass (`make verify`). No real API calls; `TestProvider` is used as the stub throughout.
