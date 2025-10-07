<!-- [![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/explain-error.svg?color=blue)](https://plugins.jenkins.io/explain-error/) -->
<p align="center">
  <img src="docs/images/logo-new.png" width="800" alt="Explain Error Plugin">
</p>

<h1 align="center">Explain Error — Jenkins Plugin</h1>
<p align="center">🤖 AI-powered plugin that explains Jenkins job failures with human-readable insights.</p>

<p align="center">
  <a href="https://plugins.jenkins.io/explain-error/">
    <img alt="Jenkins Plugin" src="https://img.shields.io/jenkins/plugin/v/explain-error.svg">
  </a>
  <a href="https://github.com/jenkinsci/explain-error-plugin/releases/latest">
    <img alt="GitHub Release" src="https://img.shields.io/github/release/jenkinsci/explain-error-plugin.svg?label=changelog">
  </a>
  <a href="https://ci.jenkins.io/job/Plugins/job/explain-error-plugin/job/main/">
    <img alt="Build Status" src="https://ci.jenkins.io/buildStatus/icon?job=Plugins%2Fexplain-error-plugin%2Fmain">
  </a>
  <img alt="License" src="https://img.shields.io/github/license/jenkinsci/explain-error-plugin">
</p>

---

## 🎥 Demo

👉 [Watch the hands-on demo on YouTube](https://youtu.be/rPI9PMeDQ2o?si=YMeprtSz9VmqglCL) — setup, run, and see how AI explains your Jenkins job failures.

---

## Overview

Tired of digging through long Jenkins logs to understand what went wrong?

**Explain Error Plugin** leverages AI to automatically interpret job and pipeline failures—saving you time and helping you fix issues faster.

Whether it’s a compilation error, test failure, or deployment hiccup, this plugin turns confusing logs into human-readable insights.

## Key Features

* 🔍 **One-click error analysis** on any console output
* ⚙️ **Pipeline-ready** with a simple `explainError()` step
* 💡 **AI-powered explanations** via OpenAI GPT models, Google Gemini or local Ollama models
* 🤖 **Smart provider management** — LangChain4j handles most providers automatically
* 🎯 **Customizable**: set provider, model, API endpoint, log filters, and more

## Quick Start

### Prerequisites

- Jenkins 2.479.3+
- Java 17+
- AI API Key (OpenAI or Google)

### Installation

1. **Install via Jenkins Plugin Manager:**
   - Go to `Manage Jenkins` → `Manage Plugins` → `Available`
   - Search for "Explain Error Plugin"
   - Click `Install` and restart Jenkins

2. **Manual Installation:**
   - Download the `.hpi` file from [releases](https://plugins.jenkins.io/explain-error/releases/)
   - Upload via `Manage Jenkins` → `Manage Plugins` → `Advanced`

### Configuration

1. Go to `Manage Jenkins` → `Configure System`
2. Find the **"Explain Error Plugin Configuration"** section
3. Configure the following settings:

| Setting | Description | Default |
|---------|-------------|---------|
| **Enable AI Error Explanation** | Toggle plugin functionality | ✅ Enabled |
| **AI Provider** | Choose between OpenAI, Google Gemini, or Ollama  | `OpenAI` |
| **API Key** | Your AI provider API key | Get from [OpenAI](https://platform.openai.com/settings) or [Google AI Studio](https://aistudio.google.com/app/apikey) |
| **API URL** | AI service endpoint | Automatically handled by LangChain4j for most providers, or specify a custom endpoint such as http://localhost:11434 for Ollama. |
| **AI Model** | Model to use for analysis | *Required*.  Specify the model name offered by your selected AI provider |

4. Click **"Test Configuration"** to verify your setup
5. Save the configuration

![Configuration](docs/images/configuration.png)

### Configuration as Code (CasC)

This plugin supports [Configuration as Code](https://plugins.jenkins.io/configuration-as-code/) for automated setup. Use the `explainError` symbol in your YAML configuration:

**OpenAI Configuration:**
```yaml
unclassified:
  explainError:
    enableExplanation: true
    provider: "OPENAI"
    apiKey: "${AI_API_KEY}"
    model: "gpt-5-nano"
```

**Google Gemini Configuration:**
```yaml
unclassified:
  explainError:
    enableExplanation: true
    provider: "GEMINI"
    apiKey: "${AI_API_KEY}"
    model: "gemini-2.0-flash"
```

**Ollama Configuration:**
```yaml
unclassified:
  explainError:
    enableExplanation: true
    provider: "OLLAMA"
    apiUrl: "http://localhost:11434"
    model: "gemma3:1b" # gpt-oss, deepseek-r1, etc
```

**Environment Variable Example:**
```bash
export AI_API_KEY="your-api-key-here"
```

This allows you to manage the plugin configuration alongside your other Jenkins settings in version control.

## Supported AI Providers

### OpenAI
- **Models**: `gpt-5-nano`, `gpt-4`, `gpt-4-turbo`
- **API Key**: Get from [OpenAI Platform](https://platform.openai.com/settings)
- **Endpoint**: Automatically handled by LangChain4j
- **Best for**: Comprehensive error analysis with excellent reasoning

### Google Gemini
- **Models**: `gemini-2.0-flash`, `gemini-2.0-pro`
- **API Key**: Get from [Google AI Studio](https://aistudio.google.com/app/apikey)
- **Endpoint**: Automatically handled by LangChain4j
- **Best for**: Fast, efficient analysis with competitive quality

### Ollama (Local/Private LLM)
- **Models**: `gemma3:1b`, `gpt-oss`, `deepseek-r1`, and any model available in your Ollama instance
- **API Key**: Not required by default (unless your Ollama server is secured)
- **Endpoint**: `http://localhost:11434` (or your Ollama server URL)
- **Best for**: Private, local, or open-source LLMs; no external API usage or cost

## Usage

### Method 1: Pipeline Step

Use `explainError()` in your pipeline (e.g., in a `post` block):

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                script {
                    // Your build steps here
                    sh 'make build'
                }
            }
        }
    }
    post {
        failure {
            // Automatically explain errors when build fails
            explainError()
        }
    }
}
```

Optional parameters:

```groovy
explainError(
  maxLines: 500,
  logPattern: '(?i)(error|failed|exception)'
)
```
Output appears in the sidebar of the failed job.

![Side Panel - AI Error Explanation](docs/images/side-panel.png)

### Method 2: Manual Console Analysis

Works with Freestyle, Declarative, or any job type.

1. Go to the failed build’s console output
2. Click **Explain Error** button in the top
3. View results directly under the button

![AI Error Explanation](docs/images/console-output.png)

## Troubleshooting

| Issue | Solution |
|-------|----------|
|API key not set	| Add your key in Jenkins global config |
|Auth or rate limit error| Check key validity, quota, and provider plan |
|Button not visible	| Ensure Jenkins version ≥ 2.479.3, restart Jenkins after installation |

Enable debug logs:

`Manage Jenkins` → `System Log` → Add logger for `io.jenkins.plugins.explain_error`

## Best Practices

1. Use `explainError()` in `post { failure { ... } }` blocks
2. Apply `logPattern` to focus on relevant errors
3. Monitor your AI provider usage to control costs
4. Keep plugin updated regularly

## Support & Community

- [GitHub Issues](https://github.com/jenkinsci/explain-error-plugin/issues) for bug reports and feature requests
- [Contributing Guide](CONTRIBUTING.md) if you'd like to help
- Security concerns? Email security@jenkins.io

## License

Licensed under the [MIT License](LICENSE.md).

## Acknowledgments

Built with ❤️ for the Jenkins community.
If you find it useful, please ⭐ us on GitHub!
