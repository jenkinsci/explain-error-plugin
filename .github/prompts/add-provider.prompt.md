---
description: "Step-by-step guide for adding a new AI provider to the explain-error-plugin. Use when implementing a new LangChain4j-based AI provider (e.g., Anthropic, Mistral)."
---

# How to Add a New AI Provider

Follow these steps in order. Each step has a corresponding file to create or modify.

## Step 1 — Create the Provider class

Create `src/main/java/io/jenkins/plugins/explain_error/provider/MyProvider.java`.

For a LangChain4j-backed API, extend `ChatModelAIProvider` and implement **one** model factory — the assistant plumbing is inherited, and the full-context signature means credentials scope and temperature cannot be silently dropped:

```java
public class MyProvider extends ChatModelAIProvider {
    private Secret apiKey;

    @DataBoundConstructor
    public MyProvider(String url, String model, Secret apiKey) {
        super(url, model);
        this.apiKey = apiKey;
    }

    @Override
    protected ChatModel createChatModel(@CheckForNull Item item, @CheckForNull Authentication authentication,
                                        @CheckForNull Double temperature) {
        var builder = SomeChatModel.builder()
                .httpClientBuilder(newLangChainHttpClientBuilder()) // Jenkins proxy support
                .baseUrl(getUrl())
                .apiKey(apiKey.getPlainText())
                .modelName(getModel());
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        return Secret.toString(apiKey).isBlank();
    }

    @Extension
    @Symbol("myProvider")
    public static class DescriptorImpl extends BaseProviderDescriptor {
        @Override public @NonNull String getDisplayName() { return "My Provider"; }
        @Override public String getDefaultModel() { return "my-default-model"; }

        @POST
        public FormValidation doTestConfiguration(@AncestorInPath Item context,
                                                  @QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model) {
            return runConfigurationTest(context, new MyProvider(url, model, apiKey));
        }
    }
}
```

Providers that call their API directly (no LangChain4j `ChatModel`) extend `BaseAIProvider` instead and implement the full-context `createAssistant(item, authentication, temperature)` and `createFixAssistant(item, authentication)` — see `AzureOpenAIProvider` or `LangGraphProvider`. The narrower `createAssistant()`/`createAssistant(temperature)` overloads are final; never try to override them.

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
- Test `explainError()` fails cleanly on missing config (`ProviderTest` pattern)
- Prefer end-to-end tests against a local `com.sun.net.httpserver.HttpServer` (see `OpenAICompatibleProviderTest`)
- Test CasC round-trip (`CasCTest` pattern)

## Step 5 — Update Documentation

- Add provider to `README.md` feature list and CasC YAML example
- Update the `AGENTS.md` Architecture section (Key Components, provider notes)

## Implementation Notes

- **Error Messages**: Use `ExplanationException` with a user-friendly message
- **Security**: Store API keys as `Secret`; validate with `Secret.toString(key).isBlank()`
- **Backward Compatibility**: If migrating config fields, add `readResolve()` migration (see `GlobalConfigurationImpl`)
- **LangChain4j**: Always exclude SLF4J and Jackson from new dependencies; use structured output via `AiServices.builder()`
- **UI Consistency**: Use Jenkins design library (`l:card`, `jenkins-button`, CSS variables for dark theme)
