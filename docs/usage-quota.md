# AI Provider Call Quotas

Usage quotas let administrators cap the number of real AI provider calls the plugin makes within a rolling time window. This prevents unexpected API cost spikes and gives you predictable spend control — both at the Jenkins controller level and at the folder level.

---

## What Is Supported

| Feature | Details |
|---|---|
| **Time windows** | Hourly or Daily |
| **Controller-level quota** | One global quota covering all jobs on the Jenkins controller |
| **Folder-level quota** | Per-folder quota that overrides the global quota for all jobs inside that folder (and its subfolders) |
| **Quota inheritance** | Quota resolution walks up the folder hierarchy; the **nearest** folder with `Enable Request Quota` turned on wins |
| **Configuration as Code** | Both global and folder-level quotas are expressible in CasC YAML |
| **Usage event tracking** | Rejected calls emit a `QUOTA_REJECTED` usage event (visible in `MetricsUsageRecorder`) |
| **Real-time config changes** | Changing the limit takes effect immediately — no Jenkins restart needed |
| **Thread safety** | The counter is synchronized, so concurrent builds on the same controller share the same window correctly |

---

## What Is Not Supported

| Limitation | Notes |
|---|---|
| **Per-user quotas** | Quota is tracked per controller / per folder, not per Jenkins user |
| **Per-job quotas** | There is no job-level quota; the smallest scope is a folder |
| **Quota persistence across restarts** | The in-memory counter resets when Jenkins restarts. A restart effectively begins a new window |
| **Cache hits count toward quota** | Only real provider calls are counted. Returning an already-stored `ErrorExplanationAction` (cache hit) does **not** consume quota |
| **More than two time windows** | Only `Hourly` and `Daily` windows are available |
| **Alerts / notifications when quota is reached** | There is no email or notification hook; the only signal is the console message and the `QUOTA_REJECTED` usage event |
| **Quota carry-over** | Unused capacity from the previous window does not roll over |

---

## How to Configure

### Controller-level quota (global)

1. Go to **Manage Jenkins → Configure System**.
2. Scroll to the **Explain Error Plugin Configuration** section.
3. Make sure **Enable AI Error Explanation** is checked.
4. Check **Enable Request Quota**.
5. Choose a **Quota Window** — `Hourly` or `Daily`.
6. Set **Max Provider Calls per Window** to the maximum number of real AI calls you want to allow in that window. Setting it to `0` blocks all calls.
7. Save.

When the quota is exceeded the plugin logs the following message to the build console and records a `QUOTA_REJECTED` event:

```
[explain-error] Provider call quota exceeded. Limit: <N> calls per <window> window.
```

#### CasC example (global)

```yaml
unclassified:
  explainError:
    enableExplanation: true
    aiProvider:
      openai:
        apiKey: "${AI_API_KEY}"
        model: "gpt-4o"
    enableQuota: true
    quotaWindow: HOURLY
    maxProviderCallsPerWindow: 50
```

---

### Folder-level quota

Folder-level quotas override the global quota for every job inside that folder (including nested subfolders). Use this when different teams have different cost budgets or SLA requirements.

1. Open the folder you want to configure and click **Configure**.
2. Scroll to the **Explain Error Configuration** section.
3. Check **Enable AI Error Explanation**.
4. Check **Enable Request Quota**.
5. Choose a **Quota Window** and set **Max Provider Calls per Window**.
6. Save.

When the folder-level quota is exceeded, the console message is:

```
[explain-error] Provider call quota exceeded (folder level). Limit: <N> calls per <window> window.
```

#### CasC example (folder)

```yaml
jobs:
  - script: |
      folder('my-team') {
        properties([
          [$class: 'ExplainErrorFolderProperty',
            enableExplanation: true,
            enableQuota: true,
            quotaWindow: 'DAILY',
            maxProviderCallsPerWindow: 20
          ]
        ])
      }
```

---

## Priority / Resolution Order

When a build runs, the plugin resolves the quota as follows:

```
Job's parent folder
  └─► Does the folder have "Enable Request Quota" turned on?
        YES → use that folder's quota (stop searching)
        NO  → check parent folder … repeat until root
  └─► No folder quota found → use global quota (if enabled)
  └─► No global quota either → no limit, all calls are allowed
```

The **nearest** folder in the hierarchy **with quota enabled** wins. A folder that has the quota checkbox unchecked is skipped — the search continues upward.

**Example scenario:**

```
Jenkins root  (global: 100 calls/hour)
└── Platform  (folder quota: disabled)
    └── Team A  (folder quota: 10 calls/day)
        └── service-api  ← quota applied: 10 calls/day (Team A)
    └── Team B  (no folder property at all)
        └── service-web  ← quota applied: 100 calls/hour (global)
```

---

## Collecting Metrics

This plugin emits usage metrics through the Jenkins Metrics plugin integration.

### Prerequisite

Install the Jenkins Metrics plugin on your controller. Without it, usage events are still created internally but no external metrics are exported.

### What to scrape

Collect metrics from your Jenkins Metrics endpoint/backend and filter by the `explain_error` prefix.

The plugin exports the following metric families:

| Metric | Type | Meaning |
|---|---|---|
| `explain_error.requests.<entryPoint>.<result>` | Counter | Total requests by entry point (`pipeline_step`, `console_action`) and outcome (`success`, `provider_error`, `quota_rejected`, etc.) |
| `explain_error.provider_calls.<provider>.<model>.<result>` | Counter | Provider/model-level outcomes for calls that involve a provider (`success`, `cache_hit`, `provider_error`, `quota_rejected`) |
| `explain_error.request_duration_ms` | Histogram | End-to-end request duration in milliseconds |
| `explain_error.input_log_lines` | Histogram | Number of input log lines processed per request |

### How this works for newly added AI providers

No extra metrics code is needed per provider as long as the provider is resolved through the standard Explain Error flow.

- Provider and model dimensions are derived from the provider display name and model value.
- Names are sanitized to low-cardinality metric segments (lowercase, unsupported characters replaced with `_`).
- Example: `My Custom Provider` + `my-model/v2.0` becomes:

```text
explain_error.provider_calls.my_custom_provider.my-model_v2_0.success
```

### Recommended dashboards

- Volume: requests by result and entry point
- Reliability: `provider_error` and `quota_rejected` rates
- Cost control proxy: provider/model call counters
- Latency: p50/p95 from `request_duration_ms`

---

## Tips

- **Setting `maxProviderCallsPerWindow: 0`** at the global level and then using folder-level quotas is a clean way to give each team an explicit allowance while preventing any uncontrolled global spend.
- **The window resets automatically.** After the time window elapses the counter resets on the next call — you do not need to manually clear it.
- **Multiple builds running at the same time** share the same counter. Ten concurrent builds all count against the same window.
- **Console action calls** (clicking the "Explain Error" button) count toward the quota in exactly the same way as `explainError()` pipeline-step calls.
