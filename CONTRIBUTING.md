# Contributing to Explain Error Plugin

Thank you for your interest in contributing to the Explain Error Plugin! 

This guide will help you get started with development and contribution.

## Quick Start

### Prerequisites

- **Java**: Version 17 or later
- **Maven**: Version 3.9 or later
- **Jenkins**: Version 2.528.3 or later for testing
- **Git**: For version control
- **IDE**: IntelliJ IDEA or VS Code recommended

### Development Setup

1. **Fork and clone the repository:**
   ```bash
   git clone https://github.com/<your-username>/explain-error-plugin.git
   cd explain-error-plugin
   ```

2. **Build the plugin:**

   A `Makefile` is provided for convenience. Run the following to see all available targets and their descriptions:

   ```bash
   make help
   ```

### 2. Plugin Installation & Testing

#### Manual Installation in Jenkins

1. **Build the plugin:**
   ```bash
   make package
   ```

2. **Install in Jenkins:**
   - Copy `target/explain-error.hpi` to your Jenkins instance
   - Go to `Manage Jenkins` → `Manage Plugins` → `Advanced`
   - Upload the `.hpi` file in the "Upload Plugin" section
   - Restart Jenkins

3. **Alternative: Direct file copy:**
   ```bash
   cp target/explain-error.hpi $JENKINS_HOME/plugins/
   # Restart Jenkins
   ```

> **Tip:** Run `make help` anytime to see all available build, test, and run targets.

#### Plugin Configuration for Development

1. **Navigate to Jenkins configuration:**
   - Go to `Manage Jenkins` → `Configure System`
   - Find "Explain Error Plugin Configuration" section

2. **Configure test settings:**
   - **Enable AI Error Explanation**
   - **API Key**: Your OpenAI API key (get from [OpenAI Dashboard](https://platform.openai.com/api-keys))
   - **API URL**: `https://api.openai.com/v1/chat/completions` (default)
   - **Model**: `gpt-3.5-turbo` or `gpt-4`

3. **Test your configuration:**
   - Click "Test Configuration" button
   - Verify the test passes before development

## Testing

### Running Tests

Run `make help` to see all available test-related targets.

### Writing Tests

We use JUnit 5, Mockito, and WireMock for testing. Examples:

```java
@ExtendWith(MockitoExtension.class)
class AIServiceTest {
    
    @Mock
    private GlobalConfigurationImpl config;
    
    @Test
    void shouldExplainError() {
        // Test implementation
    }
}
```

### Manual Testing

1. **Create a test pipeline:**
   ```groovy
   pipeline {
       agent any
       stages {
           stage('Test') {
               steps {
                   script {
                       // Intentionally fail to test error explanation
                       sh 'exit 1'
                   }
               }
           }
       }
       post {
           failure {
               explainError()
           }
       }
   }
   ```

2. **Test console button:**
   - Run any job that fails
   - Go to console output
   - Click "Explain Error" button
   - Verify explanation appears

## Development Guidelines

### Code Style

- **Java**: Follow standard Java conventions
- **Indentation**: 4 spaces (no tabs)
- **Line length**: Maximum 120 characters
- **Naming**: Use descriptive names for classes and methods

To check for code quality issues, run `make help` to find the lint target and execute it.

### Architecture

The plugin follows these patterns:

```
src/main/java/io/jenkins/plugins/explain_error/
├── GlobalConfigurationImpl.java     # Main plugin class
├── ExplainErrorStep.java            # Pipeline step (explainError + autoFix parameters)
├── ErrorExplainer.java              # Error analysis logic (provider resolution)
├── ConsoleExplainErrorAction.java   # Console button action
├── ErrorExplanationAction.java      # Build action for storing results
├── provider/
│   ├── BaseAIProvider.java          # Abstract AI service base class
│   ├── OpenAIProvider.java          # OpenAI / LangChain4j
│   ├── GeminiProvider.java          # Google Gemini / LangChain4j
│   ├── BedrockProvider.java         # AWS Bedrock / LangChain4j
│   └── OllamaProvider.java          # Ollama / LangChain4j
└── autofix/
    ├── AutoFixOrchestrator.java     # Coordinates AI suggestion → branch → PR flow
    ├── AutoFixAction.java           # Build action that stores and displays the PR URL
    ├── AutoFixResult.java           # Result value object (status + PR URL + message)
    ├── AutoFixStatus.java           # Enum: CREATED, FAILED, SKIPPED_*, NOT_APPLICABLE
    ├── FixAssistant.java            # LangChain4j AI service interface for fix suggestions
    ├── FixSuggestion.java           # Structured AI response (fixable, changes, confidence)
    ├── UnifiedDiffApplier.java      # Applies unified diffs to file content (fuzzy match)
    └── scm/
        ├── ScmApiClient.java        # Interface: createBranch, commitFiles, createPullRequest…
        ├── ScmClientFactory.java    # Factory: creates the right client from ScmRepo
        ├── ScmRepo.java             # Value object: SCM type + base URL + owner/repo + token
        ├── ScmType.java             # Enum: GITHUB, GITLAB, BITBUCKET
        ├── GitHubApiClient.java     # GitHub REST API v3 (Git Trees API for atomic commits)
        ├── GitLabApiClient.java     # GitLab REST API v4 (Commits API)
        ├── BitbucketApiClient.java  # Bitbucket Cloud REST API v2
        └── PullRequest.java         # Value object returned by createPullRequest()
```

### Adding New Features

1. **Create feature branch:**
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Follow TDD approach:**
   - Write tests first
   - Implement feature
   - Refactor and optimize

3. **Update documentation:**
   - Update README.md if needed
   - Add Javadoc comments

## Debugging

### Enable Debug Logging

1. **In Jenkins:**
   - Go to `Manage Jenkins` → `System Log`
   - Add logger: `io.jenkins.plugins.explain_error`
   - Set level to `FINE` or `ALL`

## Pull Request Process

1. **Before submitting:**
   - ✅ All tests pass
   - ✅ Full verify passes
   - ✅ No new lint issues
   - ✅ Code follows style guidelines
   - ✅ Documentation updated

   Run `make help` to see the relevant targets for each of the above steps.

2. **PR checklist:**
   - [ ] Descriptive title and description
   - [ ] Related issue linked (if applicable)
   - [ ] Tests included
   - [ ] Documentation updated
   - [ ] No breaking changes (or clearly documented)

3. **Review process:**
   - Automated tests will run
   - Maintainers will review code
   - Address feedback promptly

## Reporting Issues

### Bug Reports

Use our [bug report template](.github/ISSUE_TEMPLATE/bug_report.md):

- **Environment**: Jenkins version, plugin version, Java version
- **Steps to reproduce**: Clear, numbered steps
- **Expected behavior**: What should happen
- **Actual behavior**: What actually happens
- **Logs**: Relevant error logs or stack traces

### Feature Requests

Use our [feature request template](.github/ISSUE_TEMPLATE/feature_request.md):

- **Problem**: What problem does this solve?
- **Solution**: Proposed solution
- **Alternatives**: Alternative approaches considered
- **Additional context**: Screenshots, examples, etc.

## 🤝 Community

- 💬 **Discussions**: [GitHub Discussions](https://github.com/jenkinsci/explain-error-plugin/discussions)
- 🐛 **Issues**: [GitHub Issues](https://github.com/jenkinsci/explain-error-plugin/issues)
- 📧 **Security**: security@jenkins.io

Thank you for contributing to the Explain Error Plugin! 🎉
