# Automatic Explanation of Failed Builds

When **Automatically explain failed builds** is enabled, every build that ends with result `FAILURE` is explained by the AI provider without any change to the job or the Jenkinsfile. The explanation appears on the build page a few seconds after the build finishes.

Because one toggle covers every job on the controller, automatic explanation has limits that keep a burst of failures, for example during an outage, from turning into a burst of AI provider calls.

---

## What Is Supported

| Feature | Details |
|---|---|
| **Zero-change rollout** | One global toggle covers all jobs: Freestyle, Pipeline, Multibranch |
| **Per-job override** | A job can opt out while the toggle is on, or opt in while it is off |
| **Hourly limit** | At most **30** provider calls per hour for automatic explanations by default, independent of the request quota |
| **Bounded background work** | At most **2** explanations run at the same time and at most **50** failed builds wait for one; further failures are skipped |
| **Says when it skips** | A build that is not explained because of a limit says so in its console |
| **Reuse of identical failures** | A build that fails the same way as the last explained build of the job takes over that explanation instead of calling the provider |
| **No duplicate explanations** | Builds that already called the `explainError()` step are skipped, and so is a build someone explained by hand while it was waiting |
| **Progress on the pages** | While an explanation is on its way the build page shows a spinner, and the console button reads **Explaining…** |
| **Usage metrics** | Requests are tracked under the `run_listener` entry point |

---

## What Is Not Supported

| Limitation | Notes |
|---|---|
| **Results other than `FAILURE`** | `UNSTABLE`, `ABORTED` and `NOT_BUILT` builds are never explained automatically |
| **Per-folder toggle** | The toggle is global with a per-job override. Folders can set their own provider and quota, which automatic explanations use |
| **Console feedback after the build** | The console is written while the build ends. A provider error or a request-quota rejection that happens afterwards is written to the Jenkins log, not to the build console |
| **Persistence of limits** | The hourly counter resets when Jenkins restarts |
| **Limits for the step or the button** | `explainError()` and the **Explain Error** button are not subject to these limits; use the [request quota](usage-quota.md) for them |
| **Reuse for the step or the button** | Only automatic explanations are reused; `explainError()` and the **Explain Error** button always call the provider |
| **Reuse across jobs** | An explanation is only taken over from an earlier build of the same job |

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

The `properties` step replaces all job properties that the Jenkinsfile manages, so list `explainErrorJob` together with the other properties you set there. It takes effect from the build that runs it.

The override only decides whether a failed build is explained *automatically*:

- A job that opts out keeps its **Explain Error** button, and `explainError()` in its Pipeline still works.
- A job that opts in is subject to the same [hourly limit](#hourly-limit) and [queue](#concurrency-and-queue-limits) as every other job, and to the request quota.
- Opting in does not get past the global **Enable AI Error Explanation**: while that is off, nothing is explained, unless the job's folder has an enabled AI provider of its own.
- **Max Log Lines** and **Max Automatic Explanations per Hour** are global. They are shown in the global configuration only while **Automatically explain failed builds** is checked; with it unchecked, jobs that opt in use the values last saved there (or set through CasC).

---

## Hourly Limit

Automatic explanations are limited to **30 provider calls per hour** by default. The limit applies in addition to the optional [request quota](usage-quota.md), so automatic explanations cannot use up the whole quota and leave nothing for the `explainError()` step or the **Explain Error** button.

When the limit is reached, further failed builds in that hour are not explained automatically. Each one says so in its console and is recorded as a `quota_rejected` event for the `run_listener` entry point:

```
[explain-error] Build failed, but auto-explain was skipped because the limit of 30 automatic explanations per hour has been reached.
```

Such a build can still be explained by hand with the **Explain Error** button on its console page.

The hour is a fixed window: it starts when the first automatic explanation is counted and begins again an hour later. It is not a sliding 60 minutes.

The limit counts provider calls. A build takes its place in the count when it fails, so that its console can say at once whether it will be explained. If it then never reaches the provider (someone explained it by hand while it waited, or the request quota turned it away), the place is given back. A call the provider answered with an error still counts.

Raise **Max Automatic Explanations per Hour** if legitimate failures are being skipped.

---

## Reuse of Identical Failures

A job that keeps failing for the same reason, such as a broken main branch or a nightly job hitting the same infrastructure problem, would otherwise be sent to the provider again on every build, for the same answer. Instead, before calling the provider the plugin looks at the most recent earlier build of the job that has an explanation (up to 10 builds back). If that build failed the same way, its explanation is copied to the new build and the provider is not called.

A reused explanation says so wherever it is shown: "Reused from build #12, which failed the same way", with a link to that build's explanation. It keeps the time the explanation was generated, and names the build it was generated for, however many builds have taken it over since.

Two failures count as the same when their failure logs are equal once the parts that change on every run are left out:

- dates and times, and durations such as `0.203 s` or `1 min 3 s`
- transfer sizes and rates, such as `215 kB at 675 kB/s`
- build numbers written as `#42`
- numbers of six or more digits, such as temporary file names and process ids
- hexadecimal ids of eight or more characters, such as commit hashes and container ids
- extra spaces and blank lines

Everything else has to be equal, including version numbers, line numbers, exit codes and expected values: `requests==2.99.0` and `requests==2.98.0`, or `App.java:21` and `App.java:57`, are different failures, and the old explanation would be wrong for the new one. The match is strict on purpose. A reuse that is missed costs one provider call; a wrong one shows an explanation of a failure the build does not have.

The language, custom context, provider, model and temperature must also be the same, so an explanation is only reused when it would have been requested the same way.

What a reused explanation costs:

- It is recorded as a `cache_hit` in usage metrics.
- It does not count toward the [hourly limit](#hourly-limit) or the [request quota](usage-quota.md).
- It still waits its turn in the [queue](#concurrency-and-queue-limits), and a build that the hourly limit or a full queue has already skipped is not looked at: the match can only be made from the finished log, after that decision.

To get a fresh explanation for a build with a reused one, open its console output, click **Show explanation** and then the re-explain icon of the card. An explanation requested that way, with the **Explain Error** button or by the `explainError()` step is not passed on to later builds: only automatic explanations are.

Reuse can be switched off with the system property `io.jenkins.plugins.explain_error.ErrorExplainer.reuseLookbackBuilds=0`; any other value is the number of earlier builds to look back through. It is read on every request, so it can also be set from the script console without a restart.

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

With the defaults, a burst of failures costs at most 2 provider calls at a time and 30 in the hour, however many builds fail.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Nothing happens when a build fails | The build result must be `FAILURE`, **Enable AI Error Explanation** must be on, and automatic explanation must be on for the job: globally, or through the job's override. A job that opted out is never explained automatically |
| Console says auto-explain was skipped | The hourly limit or the queue was full; the line names which. Use the **Explain Error** button for that build, or raise the limit |
| Console says the explanation will appear, but it never does | Look in **Manage Jenkins → System Log** for `io.jenkins.plugins.explain_error`: the provider may have failed, or the request quota may have been reached |
| An explanation looks like it belongs to an earlier failure | It may have been reused; the note above it names the build it came from. Use the re-explain icon in the console's explanation card for a fresh one |
| Explanations arrive late during a burst of failures | Builds wait their turn: two at a time by default. Raise the concurrency limit with the system property above if the provider can take more |
