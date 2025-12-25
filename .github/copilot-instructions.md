# Copilot Instructions for Explain Error Plugin

## Project Overview

The Explain Error Plugin is a Jenkins plugin that provides AI-powered explanations for build failures and pipeline errors. It integrates with multiple AI providers (OpenAI, Google Gemini, Ollama) to analyze error logs and provide human-readable insights to help developers understand and resolve build issues.

## Architecture

### Key Components

- **GlobalConfigurationImpl**: Main plugin configuration class with `@Symbol("explainError")` for Configuration as Code support, handles migration from legacy enum-based configuration
- **BaseAIProvider**: Abstract base class for AI provider implementations with nested `Assistant` interface and `BaseProviderDescriptor` for extensibility
- **OpenAIProvider** / **GeminiProvider** / **OllamaProvider**: LangChain4j-based AI service implementations with provider-specific configurations
- **ExplainErrorStep**: Pipeline step implementation for `explainError()` function
- **ConsoleExplainErrorAction**: Adds "Explain Error" button to console output for manual triggering
- **ConsoleExplainErrorActionFactory**: TransientActionFactory that dynamically injects ConsoleExplainErrorAction into all runs (new and existing)
- **ErrorExplanationAction**: Build action for storing and displaying AI explanations
- **ConsolePageDecorator**: UI decorator to show explain button when conditions are met
- **ErrorExplainer**: Core error analysis logic that coordinates AI providers and log parsing
- **JenkinsLogAnalysis**: Structured record for AI response (errorSummary, resolutionSteps, bestPractices, errorSignature)
- **ExplanationException**: Custom exception for error explanation failures
- **AIProvider**: Deprecated enum for backward compatibility with old configuration format

### Package Structure

```
src/main/java/io/jenkins/plugins/explain_error/
├── GlobalConfigurationImpl.java            # Plugin configuration & CasC + migration logic
├── ExplainErrorStep.java                   # Pipeline step implementation
├── ErrorExplainer.java                     # Core error analysis logic
├── ConsoleExplainErrorAction.java          # Console button action handler
├── ConsoleExplainErrorActionFactory.java   # TransientActionFactory for dynamic injection
├── ConsolePageDecorator.java               # UI button visibility logic
├── ErrorExplanationAction.java             # Build action for results storage/display
├── JenkinsLogAnalysis.java                 # Structured AI response record
├── ExplanationException.java               # Custom exception for error handling
├── AIProvider.java                         # @Deprecated enum (backward compatibility)
└── provider/
    ├── BaseAIProvider.java                  # Abstract AI service with Assistant interface
    ├── OpenAIProvider.java                  # OpenAI/LangChain4j implementation
    ├── GeminiProvider.java                  # Google Gemini/LangChain4j implementation
    └── OllamaProvider.java                  # Ollama/LangChain4j implementation
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
- Use `@POST` for security-sensitive operations
- Follow Jenkins security best practices (permission checks)
- Use `Secret` class for sensitive configuration data

### AI Service Integration
- All AI services extend `BaseAIProvider` and implement `ExtensionPoint`
- LangChain4j integration (v1.9.1) for OpenAI, Gemini, and Ollama providers
- Structured output parsing using `JenkinsLogAnalysis` record with `@Description` annotations
- Each provider implements `createAssistant()` to build LangChain4j assistants
- Provider descriptors extend `BaseProviderDescriptor` with `@Symbol` annotations for CasC
- Graceful error handling with `ExplanationException` and fallback messages
- No direct HTTP/JSON handling - LangChain4j abstracts API communication

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
- Jenkins baseline: 2.479.3
- Java 17+ required
- LangChain4j: v1.9.1 (langchain4j, langchain4j-open-ai, langchain4j-google-ai-gemini, langchain4j-ollama)
- Key Jenkins dependencies: `jackson2-api`, `workflow-step-api`, `commons-lang3-api`
- SLF4J and Jackson exclusions to avoid conflicts with Jenkins core
- Test dependencies: `workflow-cps`, `workflow-job`, `workflow-durable-task-step`, `workflow-basic-steps`, `test-harness`
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
unclasopenai:
        apiKey: "${AI_API_KEY}"
        model: "gpt-4"
        url: ""  # Optional: leave empty for default or specify custom endpoint
      # Alternative providers:
      # gemini:
      #   apiKey: "${AI_API_KEY}"
      #   model: "gemini-2.0-flash"
      # ollama:
      #   url: "http://localhost:11434"
      #   model: "gemma3:1b"
      gemini:
        apiKey: "${AI_API_KEY}"
        model: "gpt-5"
    enableExplanation: true
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

## File Overview

- `CONTRIBUTING.md` - Developer contribution guidelines and workflow
- `pom.xml` - Maven project configuration with LangChain4j dependencies
- `src/main/resources/index.jelly` - Plugin metadata for Jenkins UI
- `src/main/resources/io/jenkins/plugins/explain_error/` - Jelly UI templates for configuration
- `src/main/resources/io/jenkins/plugins/explain_error/provider/` - Provider-specific UI config files
- `docs/images/` - Documentation screenshots and diagrams
- `.github/copilot-instructions.md` - This file - AI assistant guidance for development

## Important Files

1. **Extend `BaseAIProvider`**
   - Implement `createAssistant()` - Build LangChain4j assistant with provider-specific configuration
   - Define constructor with required parameters (url, model, apiKey as needed)
   - Use `@DataBoundConstructor` annotation

2. **Create Descriptor**
   - Extend `BaseProviderDescriptor` 
   - Add `@Extension` and `@Symbol("providerName")` annotations
   - Implement `getDefaultModel()` - Return default model name
   - Implement `getDisplayName()` - Provider display name for UI
   - Add Jelly UI configuration file in `src/main/resources/io/jenkins/plugins/explain_error/provider/`

3. **LangChain4j Integration**
   - Use appropriate langchain4j provider dependency (e.g., `langchain4j-anthropic`)
   - Build chat language model with provider's builder pattern
   - Use structured output with `JenkinsLogAnalysis` record
   - Handle provider-specific exceptions gracefully

4. **Add Tests**
   - Test assistant creation and error analysis
   - Mock LangChain4j components for unit tests
   - Test configuration validation and CasC support

When adding new AI providers:

1. Extend `BaseAIProvider`
2. Implement abstract methods:
3. **Documentation**: Update README.md and Javadoc for new features
4. **Error Messages**: Provide clear, actionable error messages using `ExplanationException`
5. **Testing**: Test with real Jenkins instances and AI providers (manual testing in Jenkins test instance)
6. **Security**: Always validate inputs and handle secrets properly using Jenkins `Secret` class
7. **Performance**: Consider API rate limits, response times, and log size limits
8. **Backward Compatibility**: Support migration from old configuration format (see `GlobalConfigurationImpl.readResolve()`)
9. **LangChain4j Best Practices**: 
   - Use structured output for consistent parsing
   - Add proper exclusions for SLF4J and Jackson to avoid conflicts
   - Handle timeout and network errors gracefully
10. **UI Consistency**: Follow Jenkins UI/UX patterns in Jelly templates
5. Add comprehensive tests

## Security Considerations

- API keys stored using Jenkins `Secret` class
- All configuration changes require ADMINISTER permission
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
