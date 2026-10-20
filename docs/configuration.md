# Configuration

Explain Error is configured globally under **Manage Jenkins → System → Explain Error Plugin Configuration**. A folder
can override most settings for the jobs in it, a job can opt out of or into automatic explanation, and the
`explainError()` step can override settings for one call. The nearest setting wins: step, then folder (the nearest
folder that sets it), then global.

## Global settings

![Global configuration](images/global-configuration.png)

| Setting | Description | Default |
|---|---|---|
| **Enable AI Error Explanation** | Turns the plugin on for every job. A folder with a provider of its own keeps explanation on while this is off | On |
| **AI Provider** | The provider, its key, endpoint and model. See [AI Providers](providers.md) for each provider's fields. **Test Configuration** sends a short request and shows a connection diagnostics report when it fails | OpenAI, `gpt-4.1` |
| **Custom Context** | Additional instructions for the AI, such as links to your knowledge base or how your team handles certain failures. Explanations are short by default (a summary of one or two sentences, up to four resolution steps, up to two best practices); ask here for more or less detail | Empty |
| **Language** | Language of the explanations, such as `English`, `中文` or `日本語`. The section labels around the explanation follow it for English, Chinese (Simplified and Traditional), Japanese, Spanish, French and German; other languages get English labels | English |
| **Temperature** | Model temperature. Leave it empty to use the provider's default ([Anthropic](providers.md#anthropic-claude) has its own rules) | Empty |
| **Enable Request Quota** | Caps the provider calls per **Quota Window** (Hourly or Daily) at **Max Provider Calls per Window**; `0` blocks every call. See [AI Provider Call Quotas](usage-quota.md) | Off; Hourly, 100 |
| **Automatically explain failed builds** | Explains every failed build without a pipeline change. See [Automatic Explanation](auto-explain.md) | Off |
| **Max Log Lines** | Log lines an automatic explanation sends, shared out among the steps that failed so that each failed parallel branch is in. The Explain Error button sends at least 200; an `explainError()` step sets its own with `maxLines`. Raise it for models with large context windows | `100` |
| **Log Pattern** | Regular expression, matched without regard to case. Only the lines it matches are sent, searched for in the whole log of each failing step; when it matches nothing, the last lines are sent as they are. Applies to automatic explanations, the Explain Error button and `explainError()` steps without a `logPattern` of their own | Empty |
| **Max Automatic Explanations per Hour** | Provider calls per hour for automatic explanations, on top of the request quota | `30` |

## Folder settings

Teams can use their own provider, model, language and instructions. In a folder's **Configure** page, check **Set AI
error explanation for this folder** under **Properties** and set:

- **Turn off AI error explanation for jobs in this folder**: no Explain Error button, no automatic explanation, and
  `explainError()` does nothing, whatever the global setting says
- **Use a different AI provider for this folder**: check it to use another provider; leave it unchecked to keep the
  provider of the parent folder or the global configuration. A folder with a provider of its own has explanation on
  even while the global **Enable AI Error Explanation** is off
- **Custom Context**, **Language** and **Temperature**: override the parent folder and the global values
- **Enable Request Quota**: a quota for this folder's jobs, used instead of the global one. See
  [AI Provider Call Quotas](usage-quota.md)

Whether a job is explained at all is decided by the nearest folder that turns explanation off or has a provider of its
own, otherwise by the global setting.

## Job settings

A job can opt out of automatic explanation, for example a flaky test job, or opt in while the global toggle is off,
with **Override automatic AI error explanation** in its configuration, or in a Pipeline:

```groovy
properties([explainErrorJob(autoExplainOnFailure: false)])
```

See [Automatic Explanation](auto-explain.md#per-job-override).

## Configuration as Code

The global settings use the `explainError` symbol. The provider block is described per provider in
[AI Providers](providers.md); the other settings go next to it:

```yaml
unclassified:
  explainError:
    aiProvider:
      openai:
        apiKey: "${OPENAI_API_KEY}"
        model: "gpt-5-mini"
    enableExplanation: true
    # language: "English"                  # Optional, English by default
    # customContext: |                     # Optional
    #   If the error is from SonarQube Scanner, link to https://example.org/sonarqube-kb
    #   If a Kubernetes manifest failed, remind about cluster-specific requirements
    # temperature: 0.7                     # Optional, empty for the provider default
    # enableQuota: true                    # Optional request quota
    # quotaWindow: HOURLY                  # HOURLY or DAILY
    # maxProviderCallsPerWindow: 100
    # enableAutoExplainOnFailure: true     # Optional, explain failed builds without pipeline changes
    # autoExplainMaxLogLines: 100          # Log lines sent per build
    # logPattern: "(?i)(error|failed)"     # Optional, only the lines it matches are sent
    # autoExplainMaxPerHour: 30            # Provider calls per hour for automatic explanations
```

The folder property is `explainErrorFolder`, with the same fields as the form; `disableExplanation: true` turns
explanation off for the folder. The job property is `explainErrorJob`.
