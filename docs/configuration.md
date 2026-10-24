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
| **Mask secrets before sending** | Replaces tokens, keys and passwords with `[REDACTED]` in everything sent to the provider. See [Masking secrets](#masking-secrets) | On |
| **Additional Redaction Patterns** | Regular expressions of your own, one per line, whose matches are masked as well | Empty |
| **Do not send a log in which something was masked** | When something is masked, nothing is sent and nothing is explained | Off |
| **Max Automatic Explanations per Hour** | Provider calls per hour for automatic explanations, on top of the request quota | `30` |

## Masking secrets

Everything sent to the AI provider is masked first: the log lines, the Custom Context, the workspace files of
`includeWorkspaceContext` and what auto-fix sends. This happens for automatic explanations, the Explain Error button
on the console and Stages pages, and the `explainError()` step alike. Jenkins already masks the credentials a build
binds with `withCredentials`; masking catches the secrets that never went through a credential, such as a token in an
environment dump, a password in a clone URL or a key a script printed.

With **Mask secrets before sending** on, these are replaced with `[REDACTED]`:

| What | Example | Sent as |
|---|---|---|
| AWS access key IDs | `AKIA…` | `[REDACTED]` |
| GitHub, GitLab, Slack, Google, Stripe, OpenAI, Anthropic and npm tokens | `ghp_…`, `github_pat_…`, `glpat-…`, `xoxb-…`, `AIza…`, `sk_live_…`, `sk-proj-…`, `sk-ant-…`, `npm_…` | `[REDACTED]` |
| JSON Web Tokens | `eyJ….eyJ….…` | `[REDACTED]` |
| Private keys, PEM and PGP, from their first line to their last, whatever comes before each line (a timestamp, a BuildKit step) and also inside a JSON string. A key whose first or last line is not in the lines sent is masked as far as its base64 lines go, when nothing comes before them; a key header printed on its own, as a secret scanner does, is masked alone | `-----BEGIN … PRIVATE KEY-----` to `-----END … PRIVATE KEY-----` | `[REDACTED]` |
| The credentials of an `Authorization` header or a bearer token | `Authorization: Bearer abc123…` | `Authorization: Bearer [REDACTED]` |
| The password of a URL, or of `curl -u` (not `docker run -u 1000:1000`) | `https://deploy:s3cret@git.example.com`, `curl -u admin:s3cret` | `https://deploy:[REDACTED]@git.example.com` |
| A value given to a key whose last word is `password`, `passwd`, `passphrase`, `secret` or `token`, or `key` after `api`, `access`, `private`, `secret`, `account`, `client` and the like; a key in capitals may run the words together (`PGPASSWORD`). As `KEY=value`, `key: value`, JSON, XML or a command-line option | `DB_PASSWORD=hunter2`, `"clientSecret": "…"`, `<password>…</password>`, `--token=…`, `-Dgpg.passphrase=…`, `AccountKey=…` | `DB_PASSWORD=[REDACTED]` |

The key stays, so the model still sees what the value was for. The key's last word has to be one of those, so
`TokenService.java:42`, `password_reset.py`, `io.jsonwebtoken:jjwt`, `token-service:` and `"max_tokens": 4096` are
left alone. In a sentence, a key of one word followed by a colon and a single word is not taken for a value:
`Invalid token: expired` says what went wrong, where `password: changeme` on a line of YAML is masked. A reference
such as `${PASSWORD}`, a value that says there is none (`null`, `undefined`, `none`) and a value Jenkins already
masked (`****`) are left as they are too. The rules only find secrets by
their shape or their name: a password printed on its own, with nothing to say what it is, is sent.

**Additional Redaction Patterns** takes regular expressions of your own, one per line, for what only you know is
sensitive, such as internal host names, IP addresses or customer IDs. They apply even with the built-in rules off. A
pattern with a group named `secret` masks that group only; any other pattern masks its whole match. Lines starting
with `#` are left out.

```text
# Internal build hosts
\b[a-z0-9-]+\.corp\.example\.com\b
# Private IP addresses
\b10(?:\.\d{1,3}){3}\b
# A customer ID, keeping its name
(?i)customer[_-]?id[ \t]*[:=][ \t]*(?<secret>\S+)
```

The explanation shows how many secrets were masked next to the number of lines analysed, and the build's REST API has
it as `redactedCount`. An `explainError()` step also says it in the build's console (`[explain-error] Masked 2 secrets
in what is sent to the AI provider.`); the Explain Error button and automatic explanations run after the build and
leave its console alone. At `FINE`, the `io.jenkins.plugins.explain_error.ErrorExplainer` logger records each
request: the job and build, the number of lines sent, the provider and model, and how many secrets were masked.

**Do not send a log in which something was masked** is for those who would rather have no explanation than trust the
masking: when anything is masked, in the log or in the context, nothing is sent and no quota is used. The
`explainError()` step returns `null` and says why in the console, the Explain Error button shows why, and an automatic
explanation leaves the build without one, saying why at `FINE` in the system log. Usage metrics count these requests
as `blocked`.

**Preview what is sent**, next to the Explain Error button on the console page, shows as plain text the log lines a
new explanation would send, masked, without sending anything or using the quota. Anyone who can read the build's log
can open it. The Custom Context is sent with those lines and masked with them; the preview counts what is masked in
it and whether that would stop the request, but does not show it.

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
    # redactSecrets: true                  # Mask tokens, keys and passwords before sending
    # redactionPatterns: |                 # Optional, more patterns to mask, one per line
    #   \b[a-z0-9-]+\.corp\.example\.com\b
    # blockWhenRedacted: false             # Send nothing when something was masked
    # autoExplainMaxPerHour: 30            # Provider calls per hour for automatic explanations
```

The folder property is `explainErrorFolder`, with the same fields as the form; `disableExplanation: true` turns
explanation off for the folder. The job property is `explainErrorJob`.
