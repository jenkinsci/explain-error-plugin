![Explain Error Plugin](docs/images/explain-error-banner.svg)

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/explain-error.svg)](https://plugins.jenkins.io/explain-error/releases/)
[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins%2Fexplain-error-plugin%2Fmain)](https://ci.jenkins.io/job/Plugins/job/explain-error-plugin/job/main/)
[![License](https://img.shields.io/github/license/jenkinsci/explain-error-plugin)](LICENSE.md)

---

Explain Error turns a failed Jenkins build into a short explanation: what broke, why, and how to fix it. It reads the
log of the step that failed, asks the AI provider you choose, and shows the answer on the build, so nobody has to dig
through a long console to find the one line that matters.

![An explanation on the console page of a failed build](docs/images/console-output.png)

🎥 [Watch the 90-second overview on YouTube](https://youtu.be/3dwNNoYePE0)

## Features

- **Explain Error button** on the console page of a failed build, and for each stage on the Stages page of
  [Pipeline Graph View](https://plugins.jenkins.io/pipeline-graph-view/)
- **Copy for AI agent** on the console page: the failure, written up for Claude Code, Codex or another coding agent
  (repository, branch, commit, the stage and command that failed, the log of the steps that failed, secrets masked),
  ready to paste where the agent can read the code and fix it. It needs no AI provider in Jenkins
- **`explainError()` Pipeline step** that returns the explanation as text or as a map of fields, for notifications and
  routing
- **Automatic explanation** of every failed build, with no pipeline change, within hourly and concurrency limits
- **Twelve AI providers**: Anthropic (Claude), AWS Bedrock, Azure OpenAI, Custom Okta AI, DeepSeek, Google Gemini,
  LangGraph Platform, Microsoft Foundry, Ollama, OpenAI, OpenAI Compatible (LiteLLM, OpenWebUI and other gateways) and
  Qwen, including private endpoints, company gateways and models you host yourself
- **Secrets masked before sending**: tokens, keys and passwords in the log are replaced with `[REDACTED]`, with
  patterns of your own and a preview of what is sent. See [Masking secrets](docs/configuration.md#masking-secrets)
- **Folder settings**, so each team can use its own provider, language and instructions
- **Request quotas and usage metrics** to keep provider costs in check
- **AI auto-fix** *(experimental)*: opens a pull request on GitHub, GitLab or Bitbucket with a proposed fix

## Getting started

Requirements: Jenkins 2.528.3 or newer, and for explanations an AI provider you can reach from the controller.
[Copy for AI agent](#with-a-coding-agent) works as soon as the plugin is installed, with no provider and no key.

1. Install **Explain Error** from **Manage Jenkins → Plugins → Available plugins**. Without internet access, download
   the `.hpi` from the [releases](https://plugins.jenkins.io/explain-error/releases/) and upload it under
   **Plugins → Advanced settings → Deploy Plugin**.
2. Open **Manage Jenkins → System** and find **Explain Error Plugin Configuration**.
3. Choose an **AI Provider**, and fill in its key, endpoint and model.
4. Click **Test Configuration**, then **Save**.

![The provider settings](docs/images/configuration.png)

The same with [Configuration as Code](https://plugins.jenkins.io/configuration-as-code/):

```yaml
unclassified:
  explainError:
    aiProvider:
      openai:
        apiKey: "${OPENAI_API_KEY}"
        model: "gpt-5-mini"
    enableExplanation: true
```

[AI Providers](docs/providers.md) lists the fields and an example for each provider, and
[Configuration](docs/configuration.md) describes every setting, folder overrides included.

## Getting an explanation

### On the console page

Open the console of a failed build and click **Explain Error**. The explanation opens above the log, as in the picture
at the top: a title that names the root cause, a summary, the steps to fix it, the log lines where it failed and, when
there are any, practices that would have prevented it. It reads up to 200 lines of the steps that failed, or the global
**Max Log Lines** when that is higher, and only the lines the global **Log Pattern** matches when one is set.
**Preview what is sent**, next to the button, shows those lines as the provider would get them, with secrets masked,
without sending anything.

Once a build has an explanation, the button reads **Show explanation**, and the card says when the explanation was
generated; the re-explain icon asks the provider again. If a request fails, the card stays open with what went wrong
and a **Try again** button.

Asking for a new explanation needs the **Job/Build** permission, because it calls the AI provider. Users who can only
read the job see the explanation a build already has, and no button when it has none. The same applies on the Stages
page.

### With a coding agent

A coding agent such as Claude Code or Codex can fix what an explanation only describes, because it works in a
checkout of the repository: it reads the code, runs the command that failed and changes what is wrong. What it lacks is
the failure, which is in Jenkins, often behind a login or on a network the agent cannot reach.

On the console page of a failed build, **Copy for AI agent** copies the failure, written up for the agent:

- the build, its result, and where its code came from: repository, branch, commit and, for a multibranch pull
  request, the pull request
- the log of the steps that failed, each with its stage and the command it ran, as the **Explain Error** button reads
  it (the global **Max Log Lines** and **Log Pattern** apply)
- the explanation the build has, if any, marked as possibly wrong
- what to do: reproduce the failure, fix the cause, and say how the fix was checked

Paste it into the agent, in a checkout of the repository. Secrets are [masked](docs/configuration.md#masking-secrets)
first, as for the provider. No AI provider is called, so the button is there without one, for anyone who can read the
build, and nothing is counted against the quota. Where the browser does not allow the page to write to the clipboard,
as on a Jenkins served over plain HTTP, the text opens in a dialog to be copied from. **Enable AI Error Explanation**,
and a folder's **Turn off AI error explanation**, also turn the button off.

### On the Stages page

With [Pipeline Graph View](https://plugins.jenkins.io/pipeline-graph-view/), the same button is on the **Stages** page
of a failed Pipeline build, called **Pipeline Overview** before Pipeline Graph View 777. It explains the stage, parallel branch or step selected
in the graph from that node's log only, and shows the explanation above the node's steps. A node that passed has no
button.

![An explanation on the Stages page](docs/images/graph-view.png)

A build keeps one explanation. Explaining another node replaces it, also on the build page and the console page; going
back to a node the explanation covers shows it again without asking the provider.

### In a Pipeline

Call `explainError()` where the build has already failed:

```groovy
post {
    failure {
        script {
            def explanation = explainError()
            slackSend(color: 'danger', message: "Build failed!\n\nAI analysis:\n${explanation}")
        }
    }
}
```

The step can read more lines, filter them with a pattern, include downstream builds and workspace files, or return a
map of fields with `returnStructured: true`. See [The explainError Pipeline Step](docs/pipeline-step.md).

### Automatically

Check **Automatically explain failed builds** in the global configuration, and every build that ends in `FAILURE` is
explained as soon as it completes, without any pipeline change. At most 30 provider calls an hour go to automatic
explanations by default, two run at a time, and a build that fails the same way as the last explained build of its job
reuses that explanation. A job can opt out, or opt in while the global toggle is off. See
[Automatic Explanation of Failed Builds](docs/auto-explain.md).

## Where explanations appear

The build page shows the title and the summary, with a link to the whole explanation; the full page is under **AI Error
Explanation** in the build's sidebar.

![The explanation on the build page](docs/images/build-page.png)

The explanation's fields are also available through the REST API at `<build-url>/error-explanation/api/json`.

## AI auto-fix *(experimental)*

With `explainError(autoFix: true, autoFixCredentialsId: '...')`, the plugin also asks the AI for a code change, commits
it to a new branch and opens a pull request for your review. It is opt-in and never merges anything. AI-generated
changes can be wrong, so review them like any other contribution. See [AI Auto-Fix](docs/auto-fix.md) for the setup,
the supported SCM services and the files it may change.

## Documentation

| Page | What it covers |
|---|---|
| [AI Providers](docs/providers.md) | Every provider's fields, models and Configuration as Code example |
| [Configuration](docs/configuration.md) | Global, folder and job settings, and Configuration as Code |
| [The explainError Pipeline Step](docs/pipeline-step.md) | Parameters, return values, downstream builds, workspace files |
| [Automatic Explanation](docs/auto-explain.md) | Explaining every failed build, its limits and per-job overrides |
| [AI Provider Call Quotas](docs/usage-quota.md) | Request quotas, metrics and dashboards |
| [AI Auto-Fix](docs/auto-fix.md) | Pull requests with AI-proposed fixes |

## Troubleshooting

| Issue | What to check |
|---|---|
| No **Explain Error** button | The button appears on finished builds that did not succeed. Check that explanation is on, globally and for the job's folder, and that **Test Configuration** succeeds. On the Stages page it needs Pipeline Graph View 777 or newer. Asking for a new explanation needs the Job/Build permission |
| Authentication or rate limit errors | The key, the provider's quota and plan, and the plugin's own [request quota](docs/usage-quota.md) |
| **Test Configuration** fails | The connection diagnostics report under the error shows the proxy decision, DNS, TCP and HTTP probe; read it bottom-up to find the layer that fails, and include it when you report an issue |

For details, add a log recorder for `io.jenkins.plugins.explain_error` at level `FINE` under
**Manage Jenkins → System Log**.

## Support and contributing

- [GitHub Issues](https://github.com/jenkinsci/explain-error-plugin/issues) for bug reports and feature requests
- [Contributing Guide](CONTRIBUTING.md) if you'd like to help
- Found a security vulnerability? Please report it following the [Jenkins vulnerability reporting process](https://www.jenkins.io/security/#reporting-vulnerabilities); do not open a public GitHub issue

If you find the plugin useful, please ⭐ it on GitHub.

## License

Licensed under the [MIT License](LICENSE.md).
