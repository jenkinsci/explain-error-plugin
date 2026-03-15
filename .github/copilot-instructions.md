# Copilot Instructions for Explain Error Plugin

## Project Overview

The Explain Error Plugin is a Jenkins plugin that provides AI-powered explanations for build failures and pipeline errors. It integrates with multiple AI providers (OpenAI, Google Gemini, AWS Bedrock, Ollama) to analyze error logs and provide human-readable insights to help developers understand and resolve build issues.

## Architecture

### Key Components

- **GlobalConfigurationImpl**: Main plugin configuration class with `@Symbol("explainError")` for Configuration as Code support, handles migration from legacy enum-based configuration
- **BaseAIProvider**: Abstract base class for AI provider implementations with nested `Assistant` interface and `BaseProviderDescriptor` for extensibility
- **OpenAIProvider** / **GeminiProvider** / **BedrockProvider** / **OllamaProvider**: LangChain4j-based AI service implementations with provider-specific configurations
- **ExplainErrorStep**: Pipeline step implementation for `explainError()` function (supports `logPattern`, `maxLines`, `language`, `customContext` parameters)
- **ExplainErrorFolderProperty**: Folder-level AI provider override — allows teams to configure their own provider without touching global settings; walks up the folder hierarchy
- **ConsoleExplainErrorAction**: Adds "Explain Error" button to console output for manual triggering
- **ConsoleExplainErrorActionFactory**: TransientActionFactory that dynamically injects ConsoleExplainErrorAction into all runs (new and existing)
- **ErrorExplanationAction**: Build action for storing and displaying AI explanations
- **ConsolePageDecorator**: UI decorator to show explain button when conditions are met
- **ErrorExplainer**: Core error analysis logic that coordinates AI providers and log parsing; resolves provider priority (step > folder > global)
- **PipelineLogExtractor**: Extracts logs from the specific failing Pipeline step node (via `FlowGraphWalker`); integrates with optional `pipeline-graph-view` plugin for deep-linking
- **JenkinsLogAnalysis**: Structured record for AI response (errorSummary, resolutionSteps, bestPractices, errorSignature)
- **ExplanationException**: Custom exception for error explanation failures
- **AIProvider**: Deprecated enum for backward compatibility with old configuration format

### Package Structure

```
src/main/java/io/jenkins/plugins/explain_error/
├── GlobalConfigurationImpl.java            # Plugin configuration & CasC + migration logic
├── ExplainErrorStep.java                   # Pipeline step (logPattern, maxLines, language, customContext)
├── ExplainErrorFolderProperty.java         # Folder-level AI provider override
├── ErrorExplainer.java                     # Core error analysis logic (provider resolution)
├── PipelineLogExtractor.java               # Failing step log extraction + pipeline-graph-view URL
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
    ├── BedrockProvider.java                 # AWS Bedrock/LangChain4j implementation
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
- Use `@NonNull` / `@CheckForNull` (from `edu.umd.cs.findbugs.annotations`) to document nullability

### AI Service Integration
- All AI services extend `BaseAIProvider` and implement `ExtensionPoint`
- LangChain4j integration (v1.11.0) for OpenAI, Gemini, AWS Bedrock, and Ollama providers
- Structured output parsing using `JenkinsLogAnalysis` record with `@Description` annotations
- Each provider implements `createAssistant()` to build LangChain4j assistants
- Provider descriptors extend `BaseProviderDescriptor` with `@Symbol` annotations for CasC
- Graceful error handling with `ExplanationException` and fallback messages
- No direct HTTP/JSON handling - LangChain4j abstracts API communication

## Testing Practices

### Test Structure
- Unit tests in `src/test/java/io/jenkins/plugins/explain_error/`
- Use JUnit 5 (`@Test`, `@WithJenkins`)
- **Never mock AI APIs directly** — use `TestProvider` (see below) to avoid real network calls
- Test both success and failure scenarios

### TestProvider Pattern

All tests that exercise AI-integrated code use `TestProvider` — a subclass of `OpenAIProvider` that overrides `createAssistant()` with a controllable in-memory implementation:

```java
// src/test/java/io/jenkins/plugins/explain_error/provider/TestProvider.java
public class TestProvider extends OpenAIProvider {
    private boolean throwError = false;
    private JenkinsLogAnalysis answer = new JenkinsLogAnalysis("Request was successful", null, null, null);
    private String lastCustomContext;

    @DataBoundConstructor
    public TestProvider() {
        super("https://localhost:1234", "test-model", Secret.fromString("test-api-key"));
    }

    @Override
    public Assistant createAssistant() {
        return (errorLogs, language, customContext) -> {
            if (throwError) throw new RuntimeException("Request failed.");
            lastCustomContext = customContext;
            return answer;
        };
    }
}
```

Use `provider.setThrowError(true)` to simulate failures, `provider.getLastCustomContext()` to assert what was passed to the AI.

### Key Test Areas
- Configuration validation and CasC support (`CasCTest`, `GlobalConfigurationImplTest`)
- Migration from legacy enum config (`ConfigMigrationTest`)
- AI service provider implementations (`provider/ProviderTest`)
- Console button visibility logic (`ConsolePageDecoratorTest`)
- Pipeline step functionality and parameters (`ExplainErrorStepTest`, `CustomContextTest`)
- Folder-level provider override (`ExplainErrorFolderPropertyTest`)
- Error explanation display (`ErrorExplanationActionTest`)
- Log extraction (`PipelineLogExtractorTest`)

## Build & Dependencies

### Maven Configuration
- Jenkins baseline: 2.528.3
- Java 17+ required
- LangChain4j: v1.11.0 (langchain4j, langchain4j-open-ai, langchain4j-google-ai-gemini, langchain4j-bedrock, langchain4j-ollama)
- Key Jenkins dependencies: `jackson2-api`, `workflow-step-api`, `commons-lang3-api`
- SLF4J and Jackson exclusions to avoid conflicts with Jenkins core
- Test dependencies: `workflow-cps`, `workflow-job`, `workflow-durable-task-step`, `workflow-basic-steps`, `test-harness`
- Key dependencies: `jackson2-api`, `workflow-step-api`, `commons-lang3-api`

### Commands

A `Makefile` is provided for convenience — run `make help` to list all targets.

| Make target | Maven equivalent | Purpose |
|---|---|---|
| `make build` | `mvn compile` | Compile the plugin |
| `make test` | `mvn test` | Run unit tests |
| `make verify` | `mvn verify` | Full CI check |
| `make package` | `mvn package -DskipTests` | Build .hpi file |
| `make run` | `mvn hpi:run` | Start Jenkins with plugin |
| `make debug` | `mvnDebug hpi:run` | Start Jenkins with remote debugger |
| `make lint` | `mvn checkstyle:checkstyle spotbugs:check` | Static analysis (non-blocking) |
| `make reinstall` | `mvn clean package install -DskipTests` | Clean rebuild + local install |

## Configuration & Usage

### Global Configuration
- Navigate to `Manage Jenkins` → `Configure System`
- Find "Explain Error Plugin Configuration" section
- Configure AI provider, API key, URL, model, and optional `customContext`

### Folder-Level Configuration
Install the plugin in a folder: `Folder` → `Configure` → `Explain Error Plugin Configuration`. Folder config overrides global config for all jobs inside. Hierarchy is resolved from the innermost folder upward (`ExplainErrorFolderProperty.findFolderProvider()`).

### Configuration as Code

```yaml
unclassified:
  explainError:
    aiProvider:
      gemini:
        apiKey: "${AI_API_KEY}"
        model: "gemini-2.5-flash"
        # url: "" # Optional, leave empty for default
    enableExplanation: true
    customContext: "Always respond in Chinese. Focus on Maven and Java issues."
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
            // Minimal usage
            explainError()

            // Full usage with all parameters
            explainError(
                logPattern: 'ERROR|FAILURE|Exception',  // Regex to filter relevant log lines
                maxLines: 200,                           // Max log lines to send to AI (default: 100)
                language: 'Chinese',                     // Response language (default: English)
                customContext: 'This is a Maven project' // Step-level context; overrides global customContext
            )
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
- `.github/instructions/code-review.instructions.md` - Detailed code review checklist and anti-patterns

## How to Add a New AI Provider

Follow these steps in order. Each step has a corresponding file to create or modify.

### Step 1 — Create the Provider class

Create `src/main/java/io/jenkins/plugins/explain_error/provider/AnthropicProvider.java`:

```java
public class AnthropicProvider extends BaseAIProvider {
    private Secret apiKey;

    @DataBoundConstructor
    public AnthropicProvider(String url, String model, Secret apiKey) {
        super(url, model);
        this.apiKey = apiKey;
    }

    @Override
    public Assistant createAssistant() {
        // Build LangChain4j model + AiServices.builder(Assistant.class).chatLanguageModel(...).build()
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        return Secret.toString(apiKey).isBlank();
    }

    @Extension
    @Symbol("anthropic")
    public static class DescriptorImpl extends BaseProviderDescriptor {
        @Override public @NonNull String getDisplayName() { return "Anthropic (Claude)"; }
        @Override public String getDefaultModel() { return "claude-3-5-sonnet-20241022"; }
    }
}
```

### Step 2 — Add Jelly UI config

Create `src/main/resources/io/jenkins/plugins/explain_error/provider/AnthropicProvider/config.jelly` with fields for `url`, `model`, and `apiKey`.

### Step 3 — Add Maven dependency

Add `langchain4j-anthropic` to `pom.xml` with SLF4J and Jackson exclusions (see Dependency Management section).

### Step 4 — Add Tests

Create `src/test/java/io/jenkins/plugins/explain_error/provider/AnthropicProviderTest.java`:
- Test `isNotValid()` with blank/null API key
- Test `createAssistant()` throws on missing config
- Test CasC round-trip (`CasCTest` pattern)

### Step 5 — Update Documentation

- Add provider to `README.md` feature list and CasC YAML example
- Update `copilot-instructions.md` provider list and Key Components

### Best Practices
- **Documentation**: Add Javadoc to all public methods
- **Error Messages**: Use `ExplanationException` with a severity level (`"warning"` or `"error"`) and user-friendly message
- **Security**: Store API keys as `Secret`, validate with `Secret.toString(key).isBlank()`
- **Performance**: Consider API rate limits; `maxLines` limits log size sent to AI
- **Backward Compatibility**: If migrating config fields, add `readResolve()` migration (see `GlobalConfigurationImpl`)
- **LangChain4j**: Exclude SLF4J and Jackson from new dependencies; use structured output via `AiServices.builder()`
- **UI Consistency**: Use Jenkins design library (`l:card`, `jenkins-button`, CSS variables for dark theme)

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
8. **Language**: Use English for code, comments, and documentation
