# Automatic Explanation of Failed Builds

When **Automatically explain failed builds** is enabled, every build that ends with result `FAILURE` is explained by the AI provider without any change to the job or the Jenkinsfile. The explanation appears on the build page a few seconds after the build finishes.

Because it covers every job on the controller, automatic explanation has safeguards that keep a burst of failures, for example during an outage, from turning into a burst of AI provider calls.

---

## What Is Supported

| Feature | Details |
|---|---|
| **Zero-change rollout** | One global toggle covers all jobs: Freestyle, Pipeline, Multibranch |
| **Per-job override** | A job can opt out while the toggle is on, or opt in while it is off |
| **Reuse of identical failures** | A build that fails the same way as the last explained build of the job reuses that explanation instead of calling the provider |
| **Hourly limit** | At most **30** provider calls per hour for automatic explanations by default, independent of the request quota |
| **Bounded background work** | At most **2** explanations run at the same time and at most **50** failed builds wait for one; further failures are skipped |
| **No duplicate explanations** | Builds that already called the `explainError()` step are skipped |
| **Usage metrics** | Requests are tracked under the `run_listener` entry point |

---

## What Is Not Supported

| Limitation | Notes |
|---|---|
| **Results other than `FAILURE`** | `UNSTABLE`, `ABORTED` and `NOT_BUILT` builds are never explained automatically |
| **Folder-level toggle** | The toggle is global with a per-job override; folders can set their own provider and quota, which automatic explanations use |
| **Console feedback after the build** | The build console only shows that an explanation was requested or skipped. A later provider error, quota rejection or hourly limit is written to the Jenkins log, not to the build console |
| **Reuse for the pipeline step or console button** | Only automatic explanations reuse earlier explanations; `explainError()` and the **Explain Error** button always call the provider |
| **Persistence of limits** | The hourly counter resets when Jenkins restarts |

---

## How to Enable

1. Go to **Manage Jenkins → System**.
2. Find the **Explain Error Plugin Configuration** section and make sure **Enable AI Error Explanation** is checked.
3. Check **Automatically explain failed builds**.
4. Optionally adjust:
   - **Max Log Lines** (default `100`): console lines read per build. Increase it for models with large context windows.
   - **Max Automatic Explanations per Hour** (default `30`): see [Hourly limit](#hourly-limit).
5. Save.

### CasC example

```yaml
unclassified:
  explainError:
    enableExplanation: true
    aiProvider:
      openai:
        apiKey: "${AI_API_KEY}"
        model: "gpt-4o"
    enableAutoExplainOnFailure: true
    autoExplainMaxLogLines: 100
    autoExplainMaxPerHour: 30
```

---

## Per-Job Override

A job can override the global toggle:

- **Opt out** a job that fails often, such as a flaky test job, while automatic explanation is enabled.
- **Opt in** a single job while automatic explanation is disabled globally, for example to try it on one team's jobs first.

In the job configuration, check **Override automatic AI error explanation** in the **General** section and choose **Explain automatically** or **Do not explain automatically**. Leave it unchecked to follow the global setting.

In a Pipeline, use the `properties` step:

```groovy
properties([explainErrorJob(autoExplainOnFailure: false)])
```

The `properties` step replaces all job properties that the Jenkinsfile manages, so list `explainErrorJob` together with the other properties you set there.

---

## Reuse of Identical Failures

A job that keeps failing for the same reason, such as a broken main branch or a nightly job hitting the same infrastructure problem, would otherwise get the same explanation again on every build. Instead, before calling the provider the plugin looks at the most recent earlier build of the job that has an explanation (up to 10 builds back). If that build failed the same way, its explanation is copied to the new build and the provider is not called. The build page then shows which build the explanation came from.

Two failures count as the same when their failure logs match after these values are ignored:

- numbers, such as timestamps, durations, build numbers, line numbers and temporary file names
- hexadecimal ids of eight or more characters, such as commit hashes and container ids
- extra spaces and blank lines

The language, custom context, provider and model must also be the same. A reused explanation is recorded as a `cache_hit` in usage metrics and does not count toward the hourly limit or the request quota.

To get a fresh explanation for a build with a reused one, open its console output, click **Explain Error** and then the **Re-explain** button of the explanation panel.

---

## Hourly Limit

Automatic explanations are limited to **30 provider calls per hour** by default. The limit applies in addition to the optional [request quota](usage-quota.md), so automatic explanations cannot use up the whole quota and leave nothing for the `explainError()` step or the **Explain Error** button.

When the limit is reached, further failed builds in that hour are not explained. Each skipped build is logged in the Jenkins log and recorded as a `quota_rejected` event for the `run_listener` entry point:

```
<job> #<build>: Automatic explanation limit reached. Limit: 30 calls per hour.
```

Raise **Max Automatic Explanations per Hour** if legitimate failures are being skipped.

---

## Concurrency and Queue Limits

Explanations run on a background thread pool so they never delay the build. The pool is bounded:

| System property | Default | Meaning |
|---|---|---|
| `io.jenkins.plugins.explain_error.ErrorRunListener.maxConcurrentRequests` | `2` | Explanations requested from the provider at the same time |
| `io.jenkins.plugins.explain_error.ErrorRunListener.maxQueuedRequests` | `50` | Failed builds waiting for an explanation |

When the queue is full, the failed build is not explained. Its console shows:

```
[explain-error] Build failed, but auto-explain was skipped because too many failed builds are already waiting for an explanation.
```

and a `throttled` event is recorded for the `run_listener` entry point. System properties are read at startup; set them with `-D` on the Jenkins controller's Java command line.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Nothing happens when a build fails | The build result must be `FAILURE`, **Enable AI Error Explanation** must be on, and the job must not opt out |
| Console says the explanation will appear, but it never does | Look in **Manage Jenkins → System Log** for `io.jenkins.plugins.explain_error`: the provider may have failed, or the hourly limit or request quota may have been reached |
| An explanation looks like it belongs to another failure | It may have been reused from an earlier build; the build page names that build. Use **Re-explain** in the console's explanation panel to request a fresh one |
| Builds are skipped during bursts of failures | Raise the queue and concurrency limits with the system properties above |
