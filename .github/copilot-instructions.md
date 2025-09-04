# Copilot Instructions for Explain Error Plugin

## Project Overview

The Explain Error Plugin is a Jenkins plugin that provides AI-powered explanations for build failures and pipeline errors. It integrates with multiple AI providers (OpenAI, Google Gemini) to analyze error logs and provide human-readable insights to help developers understand and resolve build issues.

## Architecture

### Key Components

- **GlobalConfigurationImpl**: Main plugin configuration class with `@Symbol("explainError")` for Configuration as Code support
- **BaseAIService**: Abstract base class for AI provider implementations
- **OpenAIService** / **GeminiService**: Provider-specific AI service implementations
- **ExplainErrorStep**: Pipeline step implementation for `explainError()` function
- **ConsoleExplainErrorAction**: Adds "Explain Error" button to console output
- **ErrorExplanationAction**: Build action for storing and displaying AI explanations
- **ConsolePageDecorator**: UI decorator to show explain button when conditions are met

### Package Structure

```
src/main/java/io/jenkins/plugins/explain_error/
├── GlobalConfigurationImpl.java     # Plugin configuration & CasC
├── ExplainErrorStep.java            # Pipeline step implementation
├── BaseAIService.java               # Abstract AI service base
├── OpenAIService.java               # OpenAI API integration
├── GeminiService.java               # Google Gemini API integration
├── AIService.java                   # Service factory
├── ErrorExplainer.java              # Core error analysis logic
├── ConsoleExplainErrorAction.java   # Console button action
├── ConsolePageDecorator.java        # UI button visibility logic
└── ErrorExplanationAction.java      # Build action for results
```

## Coding Standards

### Java Conventions
- **Indentation**: 4 spaces (no tabs)
- **Line length**: Maximum 120 characters
- **Naming**: Descriptive names for classes and methods
- **Logging**: Use `java.util.logging.Logger` for consistency with Jenkins
- **Error Handling**: Comprehensive exception handling with user-friendly messages

### Jenkins Plugin Patterns
- Use `@Extension` for Jenkins extension points
- Use `@Symbol` for Configuration as Code support
- Use `@RequirePOST` for security-sensitive operations
- Follow Jenkins security best practices (permission checks)
- Use `Secret` class for sensitive configuration data

### AI Service Integration
- All AI services extend `BaseAIService`
- HTTP requests use Java 11's `HttpClient`
- JSON parsing with Jackson (`ObjectMapper`)
- Provider-specific request/response formats handled in subclasses
- Graceful error handling with fallback messages

## Testing Practices

### Test Structure
- Unit tests in `src/test/java/io/jenkins/plugins/explain_error/`
- Use JUnit 5 (`@Test`, `@WithJenkins`)
- Mock external dependencies (AI APIs)
- Test both success and failure scenarios

### Key Test Areas
- Configuration validation and CasC support
- AI service provider implementations
- Console button visibility logic
- Pipeline step functionality
- Error explanation display

## Build & Dependencies

### Maven Configuration
- Parent: `org.jenkins-ci.plugins:plugin:5.21`
- Jenkins baseline: 2.479.3+
- Java 17+ required
- Key dependencies: `jackson2-api`, `workflow-step-api`, `commons-lang3-api`

### Commands
- `mvn compile` - Compile the plugin
- `mvn test` - Run unit tests
- `mvn hpi:run` - Start Jenkins with plugin for testing
- `mvn package` - Build .hpi file

## Configuration & Usage

### Global Configuration
- Navigate to `Manage Jenkins` → `Configure System`
- Find "Explain Error Plugin Configuration" section
- Configure AI provider, API key, URL, and model

### Configuration as Code
```yaml
unclassified:
  explainError:
    enableExplanation: true
    provider: "OPENAI"  # or "GEMINI"
    apiKey: "${AI_API_KEY}"
    apiUrl: "https://api.openai.com/v1/chat/completions"
    model: "gpt-3.5-turbo"
```

### Pipeline Usage
```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                // Your build steps
            }
        }
    }
    post {
        failure {
            explainError()  // Analyze failure and add explanation
        }
    }
}
```

## Important Files

- `README.md` - Comprehensive user documentation
- `CONTRIBUTING.md` - Developer contribution guidelines
- `pom.xml` - Maven project configuration
- `src/main/resources/` - Jenkins UI templates and static resources
- `docs/images/` - Documentation screenshots

## AI Service Development

When adding new AI providers:

1. Extend `BaseAIService`
2. Implement abstract methods:
   - `buildHttpRequest()` - Provider-specific authentication/headers
   - `buildRequestBody()` - JSON request format
   - `parseResponse()` - Extract text from JSON response
3. Add provider to `AIProvider` enum
4. Update UI dropdowns in global configuration
5. Add comprehensive tests

## Security Considerations

- API keys stored using Jenkins `Secret` class
- All configuration changes require ADMINISTER permission
- HTTP requests have reasonable timeouts
- Input validation on all user-provided data
- No logging of sensitive information (API keys, responses)

## Debugging

Enable debug logging:
1. Go to `Manage Jenkins` → `System Log`
2. Add logger: `io.jenkins.plugins.explain_error`
3. Set level to `FINE` or `ALL`

## Best Practices for Contributors

1. **Follow TDD**: Write tests first, then implement features
2. **Minimal Changes**: Make surgical, focused modifications
3. **Documentation**: Update README.md and Javadoc for new features
4. **Error Messages**: Provide clear, actionable error messages
5. **Testing**: Test with real Jenkins instances and AI providers
6. **Security**: Always validate inputs and handle secrets properly
7. **Performance**: Consider API rate limits and response times