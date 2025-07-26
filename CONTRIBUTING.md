# Contributing to Explain Error Plugin

Thank you for your interest in contributing to the Explain Error Plugin! 

This guide will help you get started with development and contribution.

## Quick Start

### Prerequisites

- **Java**: Version 17 or later
- **Maven**: Version 3.9 or later
- **Jenkins**: Version 2.479.3 or later for testing
- **Git**: For version control
- **IDE**: IntelliJ IDEA or VS Code recommended

### Development Setup

1. **Fork and clone the repository:**
   ```bash
   git clone https://github.com/<your-username>/explain-error-plugin.git
   cd explain-error-plugin
   ```

2. **Build the plugin:**

   ```bash
   # Clean and compile
   mvn clean compile
   
   # Package the plugin
   mvn clean package

   # Package without running tests (useful for development)
   mvn clean package -DskipTests
   ```

3. **Run Jenkins locally with the plugin:**
   ```bash
   # Start Jenkins on http://localhost:8080
   mvn hpi:run
   
   # Or on a custom port
   mvn hpi:run -Dport=5000

   # check for code quality issues
   mvn spotbugs:check
   ```

### 2. Plugin Installation & Testing

#### Manual Installation in Jenkins

1. **Build the plugin:**
   ```bash
   mvn clean package -DskipTests
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

```bash
# Run unit tests
mvn test

# Run integration tests
mvn verify

# Skip tests during development (not recommended for PRs)
mvn clean package -DskipTests
```

### Writing Tests

We use JUnit 5 and Mockito for testing. Examples:

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

### Architecture

The plugin follows these patterns:

```
src/main/java/io/jenkins/plugins/explain_error/
├── GlobalConfigurationImpl.java     # Main plugin class
├── ExplainErrorStep.java            # Pipeline step implementation
├── AIService.java                   # AI communication service
├── ErrorExplainer.java              # Error analysis logic
├── ConsoleExplainErrorAction.java   # Console button action
└── ErrorExplanationAction.java      # Build action for storing results
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
   - ✅ Code follows style guidelines
   - ✅ Documentation updated

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
