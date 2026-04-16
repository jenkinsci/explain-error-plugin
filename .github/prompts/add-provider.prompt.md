---
description: "Step-by-step guide for adding a new AI provider to the explain-error-plugin. Use when implementing a new LangChain4j-based AI provider (e.g., Anthropic, Mistral)."
---

# How to Add a New AI Provider

Follow these steps in order. Each step has a corresponding file to create or modify.

## Step 1 — Create the Provider class

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

## Step 2 — Add Jelly UI config

Create `src/main/resources/io/jenkins/plugins/explain_error/provider/AnthropicProvider/config.jelly` with fields for `url`, `model`, and `apiKey`.

## Step 3 — Add Maven dependency

Add the LangChain4j dependency to `pom.xml` with SLF4J and Jackson exclusions to avoid conflicts with Jenkins core:

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-anthropic</artifactId>
    <version>${langchain4j.version}</version>
    <exclusions>
        <exclusion>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </exclusion>
        <exclusion>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

## Step 4 — Add Tests

Create `src/test/java/io/jenkins/plugins/explain_error/provider/AnthropicProviderTest.java`:
- Test `isNotValid()` with blank/null API key
- Test `createAssistant()` throws on missing config
- Test CasC round-trip (`CasCTest` pattern)

## Step 5 — Update Documentation

- Add provider to `README.md` feature list and CasC YAML example
- Update `copilot-instructions.md` provider list and Key Components

## Implementation Notes

- **Error Messages**: Use `ExplanationException` with a user-friendly message
- **Security**: Store API keys as `Secret`; validate with `Secret.toString(key).isBlank()`
- **Backward Compatibility**: If migrating config fields, add `readResolve()` migration (see `GlobalConfigurationImpl`)
- **LangChain4j**: Always exclude SLF4J and Jackson from new dependencies; use structured output via `AiServices.builder()`
- **UI Consistency**: Use Jenkins design library (`l:card`, `jenkins-button`, CSS variables for dark theme)
