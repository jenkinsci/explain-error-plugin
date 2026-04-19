# AI Auto-Fix

> ⚠️ **Experimental.** Auto-fix is opt-in and disabled by default. AI-generated code changes
> can be incorrect, incomplete, or even introduce new bugs. **Always review the pull request
> carefully before merging.**

## What it does

When a build fails and `autoFix: true` is set, the plugin runs this workflow automatically:

1. Sends the error logs to your configured AI provider and asks for a fix suggestion
2. Validates every proposed file path (no path traversal, must match the allow-list)
3. Validates every unified diff before touching any branch
4. Creates a new branch (`fix/jenkins-ai-<build#>-<timestamp>`) via the SCM REST API
5. Applies the diffs and commits all changes atomically
6. Opens a pull request with a structured description (root cause, file changes, build link)
7. Stores the PR URL in the Jenkins build sidebar under **"AI Auto-Fix"**

If any step fails (invalid diff, missing credentials, API error), the branch is deleted and the
build is marked with status `FAILED` — the original build result is not affected.

---

## Prerequisites

| Requirement | Details |
|-------------|---------|
| Jenkins | 2.528.3 or higher |
| Java | 17+ |
| AI provider | Any supported provider configured in global settings (OpenAI, Gemini, Bedrock, Ollama, Custom Okta) |
| SCM | GitHub.com, GitHub Enterprise, GitLab.com, GitLab self-managed, Bitbucket Cloud, or Bitbucket Server / Data Center |
| Token | Personal Access Token (PAT) with **read + write** access to the repository |


---

## Step 1 — Create a PAT and store it in Jenkins

The plugin uses a **Jenkins StringCredentials** (plain secret text) — not SSH keys, not
username/password credentials.

### GitHub

1. Go to <https://github.com/settings/tokens> → **Generate new token (classic)**
2. Grant scopes: `repo` (all)
3. Copy the token

For **GitHub Enterprise**, use `https://<your-ghe-host>/settings/tokens`.

### GitLab

1. Go to **User Settings → Access Tokens** (or **Project → Settings → Access Tokens** for a
   project-scoped token)
2. Grant scopes: `api` (or at minimum `read_repository` + `write_repository`)
3. Copy the token

### Bitbucket Cloud

1. Go to **Personal Settings → App passwords**
2. Grant permissions: **Repositories: Read + Write**, **Pull requests: Read + Write**
3. Copy the generated app password (format: `username:apppassword` is **not** what you store —
   store only the app password value; set your Bitbucket username separately if needed)

> Bitbucket Cloud uses `username:apppassword` as the HTTP Basic Auth credential.
> Store only the app-password value as a StringCredentials in Jenkins; the plugin uses
> the repository owner from the remote URL as the username.

### Add to Jenkins

1. **Manage Jenkins → Credentials → (global) → Add Credentials**
2. Kind: **Secret text**
3. Secret: paste your token
4. ID: choose a memorable ID (e.g. `github-autofix-pat`)

---

## Step 2 — Add `explainError` to your Jenkinsfile

### Minimal example (GitHub)

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                sh 'mvn clean verify'
            }
        }
    }
    post {
        failure {
            explainError(
                autoFix: true,
                autoFixCredentialsId: 'github-autofix-pat'
            )
        }
    }
}
```

The plugin automatically detects the remote URL from the job's SCM configuration (the same
repository the build checks out from). A PR is opened on that repository.

### With an explicit remote URL (Pipeline jobs)

Pipeline jobs that use `checkout scm` inside a `script` block, or multi-branch pipelines with
complex SCM configs, may not expose the remote URL automatically. Provide it explicitly:

```groovy
explainError(
    autoFix: true,
    autoFixCredentialsId: 'github-autofix-pat',
    autoFixRemoteUrl: 'https://github.com/my-org/my-repo'
)
```

### Self-hosted GitHub Enterprise

```groovy
explainError(
    autoFix: true,
    autoFixCredentialsId: 'ghe-pat',
    autoFixScmType: 'github',
    autoFixGithubEnterpriseUrl: 'https://github.mycompany.com'
)
```

### Self-hosted GitLab

```groovy
explainError(
    autoFix: true,
    autoFixCredentialsId: 'gitlab-pat',
    autoFixScmType: 'gitlab',
    autoFixGitlabUrl: 'https://gitlab.mycompany.com'
)
```

### Bitbucket Cloud

```groovy
explainError(
    autoFix: true,
    autoFixCredentialsId: 'bitbucket-app-password',
    autoFixScmType: 'bitbucket'
)
```

### Bitbucket Server / Data Center

Bitbucket Server uses a completely different REST API (`/rest/api/1.0`) from Bitbucket Cloud.
URL auto-detection works for standard Server clone URLs; use `autoFixScmType: 'bitbucketserver'`
to force it for non-standard hostnames.

**Create a PAT on Bitbucket Server:**

1. Log in → **Account Settings → HTTP access tokens**
2. Click **Create token**
3. Grant permissions: **Repository: Write**, **Pull requests: Write**
4. Copy the generated token

> HTTP access tokens require Bitbucket Server **5.5+** (Data Center 7.x+).
> For older instances, store credentials as `username:password` — the client will use Basic Auth.

**Auto-detection (SSH clone URL):**

Bitbucket Server SSH clone URLs look like `ssh://git@bitbucket.company.com:7999/PROJ/repo.git`.
The plugin detects the port-7999 SSH scheme and configures Server API automatically.

```groovy
explainError(
    autoFix: true,
    autoFixCredentialsId: 'bitbucket-server-pat'
    // autoFixScmType not needed — auto-detected from ssh://.../:7999/... URL
)
```

**Auto-detection (HTTPS clone URL):**

Bitbucket Server HTTPS clone URLs use `/scm/` in the path:
`https://bitbucket.company.com/scm/PROJ/repo.git`.

```groovy
explainError(
    autoFix: true,
    autoFixCredentialsId: 'bitbucket-server-pat'
    // auto-detected from .../scm/... URL
)
```

**Manual override (custom hostname):**

```groovy
explainError(
    autoFix: true,
    autoFixCredentialsId: 'bitbucket-server-pat',
    autoFixScmType: 'bitbucketserver',
    autoFixBitbucketUrl: 'https://bitbucket.company.com'
)
```

> `autoFixBitbucketUrl` is the base URL of your Bitbucket Server instance (no `/rest/api/1.0` suffix —
> the plugin appends it automatically).



## Step 3 — Check the result

After the build completes:

1. Open the build page in Jenkins
2. Click **"AI Auto-Fix"** in the left sidebar
3. If a PR was created, you'll see a button linking directly to it
4. If the fix was skipped or failed, the status message explains why

The PR description includes:
- Root cause summary from the AI
- List of changed files with one-line descriptions
- Build details (job name, build number, fix type, confidence level)

---

## All parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `autoFix` | boolean | `false` | Enable auto-fix. Must be `true` to activate the feature |
| `autoFixCredentialsId` | string | `''` | **Required.** Jenkins StringCredentials ID for the SCM token |
| `autoFixRemoteUrl` | string | `''` | SCM remote URL. Auto-detected from job SCM config if empty |
| `autoFixScmType` | string | `''` | Force SCM type: `github`, `gitlab`, `bitbucket`, or `bitbucketserver`. Required for self-hosted instances whose hostname is not `github.com`, `gitlab.com`, or `bitbucket.org` |
| `autoFixGithubEnterpriseUrl` | string | `''` | Base URL of GitHub Enterprise (e.g. `https://github.mycompany.com`) |
| `autoFixGitlabUrl` | string | `''` | Base URL of self-hosted GitLab (e.g. `https://gitlab.mycompany.com`) |
| `autoFixBitbucketUrl` | string | `''` | Base URL of Bitbucket Server (e.g. `https://bitbucket.mycompany.com`). Leave empty for Bitbucket Cloud |
| `autoFixAllowedPaths` | string | see below | Comma-separated glob patterns for files the AI may modify |
| `autoFixDraftPr` | boolean | `false` | Open the PR as a draft (GitHub and GitLab only) |
| `autoFixTimeoutSeconds` | int | `120` | Max seconds to wait for the entire auto-fix workflow |
| `autoFixPrTemplate` | string | `''` | Custom Markdown template for the PR body (see [PR template](#pr-body-template)) |

### Default allowed paths

```
pom.xml, build.gradle, build.gradle.kts, *.properties, *.yml, *.yaml,
Jenkinsfile, Dockerfile, package.json, requirements.txt, go.mod
```

Files outside this list are never modified, regardless of what the AI suggests.

---

## PR body template

The default PR body looks like this:

```markdown
## AI Auto-Fix for my-job #42

This pull request was automatically generated by the Explain Error Plugin
to address a build failure.

### Root Cause
<AI explanation>

### Changes
- **pom.xml** (modify): Bump jackson-databind from 2.14.0 to 2.15.2

### Build Details
- **Job:** my-folder/my-job
- **Build:** #42
- **Fix Type:** dependency
- **Confidence:** high
```

To customise it, pass a Markdown string with `{placeholder}` tokens:

```groovy
explainError(
    autoFix: true,
    autoFixCredentialsId: 'github-autofix-pat',
    autoFixPrTemplate: '''
## Auto-fix — {jobName} #{buildNumber}

**Root cause:** {explanation}

**Files changed:** {changesSummary}

_Confidence: {confidence} | Type: {fixType}_
'''
)
```

Available placeholders: `{jobName}`, `{buildNumber}`, `{explanation}`, `{changesSummary}`,
`{fixType}`, `{confidence}`.

---

## What kinds of failures can auto-fix actually handle?

Auto-fix works best for failures where the root cause is **specific and mechanical**:

| Likely to work ✅ | Unlikely to work ❌ |
|-------------------|---------------------|
| Outdated/missing dependency version in `pom.xml` or `build.gradle` | Failing unit tests with logic errors |
| Missing or wrong property in `*.properties` / `*.yml` | Complex refactoring required |
| Wrong Java/Python/Node version in `Dockerfile` or CI config | Environment-specific issues (missing secrets, network) |
| Syntax error in `Jenkinsfile` or `*.yaml` | Flaky tests |
| Missing `package.json` dependency | Multi-file architectural changes |

The AI sets a `confidence` level (`high`, `medium`, `low`). The plugin only proceeds when
confidence is `high` or `medium`. Even then, treat the PR as a **starting point**, not a
guaranteed fix.

---

## Security considerations

- The token is retrieved from Jenkins Credentials at runtime and **never logged**
- The branch name is `fix/jenkins-ai-<build#>-<timestamp>` — no user-controlled input
- File paths are validated against: absolute-path check, path-traversal check (`../`), and the
  allow-list globs — in that order — before any branch is created
- The diff is parsed and validated before it is applied; malformed diffs abort the workflow
- The token has the minimum scope needed: write access to the single repository

The plugin does **not** auto-merge. A human must review and merge the PR.

---

## Troubleshooting

### "autoFixCredentialsId is required for auto-fix"

You set `autoFix: true` but forgot `autoFixCredentialsId`. Add the credentials ID:

```groovy
explainError(autoFix: true, autoFixCredentialsId: 'my-github-pat')
```

### "SCM credentials not found for ID: …"

The credentials ID you provided does not exist in Jenkins, or the build does not have
permission to access it. Check **Manage Jenkins → Credentials**.

### "No SCM configured on this job" / "Job type … does not support SCM URL extraction"

The plugin could not determine the remote URL from the job's SCM config. Provide it explicitly:

```groovy
explainError(
    autoFix: true,
    autoFixCredentialsId: 'my-pat',
    autoFixRemoteUrl: 'https://github.com/org/repo'
)
```

### "File path '…' is not allowed by configured path patterns"

The AI suggested modifying a file not in `autoFixAllowedPaths`. Either add the pattern:

```groovy
autoFixAllowedPaths: 'pom.xml,build.gradle,src/main/**/*.java'
```

Or, if the suggestion looks wrong, the AI may have misidentified the root cause — the
explanation step output can help diagnose this.

### "Skipped: AI confidence too low or not fixable"

The AI returned `fixable: false` or `confidence: low`. This is the expected behaviour for
failures that cannot be automatically resolved. Check the explanation for root cause details.

### Auto-fix timed out

Increase `autoFixTimeoutSeconds` (default 120 s). The branch is deleted automatically on
timeout so no orphaned branches are left behind.

### Draft PR not appearing as draft

Draft PRs are only supported on **GitHub** and **GitLab**. Bitbucket Cloud and Bitbucket Server
do not have a native draft PR concept; the PR will be created as a regular open PR regardless of
`autoFixDraftPr: true`.

---

## Known limitations

- **Bitbucket Server multi-file commits**: Unlike Bitbucket Cloud (atomic), Bitbucket Server
  commits each file separately in sequence. This is not atomic, but the fix runs on a dedicated
  branch so partial commits are harmless.
- **Multi-SCM jobs**: only the first configured SCM remote is used for URL extraction.
- **SSH remotes**: SSH URLs (`git@github.com:org/repo.git`) are parsed and converted to HTTPS
  for API calls. The PAT must be a HTTPS token, not an SSH key.
- **AI diff quality**: LLM-generated unified diffs are imperfect. Expect occasional failures
  where the diff context lines don't match the actual file. A fuzzy ±3-line matcher handles
  minor offsets, but large context mismatches still fail cleanly (with rollback).
- **Pipeline SCM detection**: works with `GitSCM` (git plugin) and `CpsScmFlowDefinition`.
  Custom SCM plugins that don't expose `getRepositories()` require `autoFixRemoteUrl`.
