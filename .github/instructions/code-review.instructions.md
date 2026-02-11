---
applyTo: "**/*.java, **/*.xml, **/*.jelly, **/*.js"
---

# Code Review Instructions for Explain Error Plugin

This document outlines code review standards and best practices for the Explain Error Plugin, based on feedback from Jenkins community reviewers and hosting requirements.

## Table of Contents

1. [Logging Practices](#logging-practices)
2. [Security](#security)
3. [Code Architecture](#code-architecture)
4. [UI/UX Standards](#uiux-standards)
5. [Configuration as Code (CasC)](#configuration-as-code-casc)
6. [Error Handling](#error-handling)
7. [Dependency Management](#dependency-management)
8. [Testing Requirements](#testing-requirements)
9. [Jelly and Frontend](#jelly-and-frontend)

---

## Logging Practices

### ❌ DON'T: Abuse Java Logging

```java
// BAD: Using INFO level for tracing
LOGGER.info("Starting error explanation for build " + buildNumber);
LOGGER.info("Sending request to AI provider");
LOGGER.info("Received response from AI provider");
```

**Why**: Jenkins logs should not be spammed with trace-level information. This makes it hard for admins to find real issues.

### ✅ DO: Use Appropriate Log Levels

```java
// GOOD: Use FINE for debugging, INFO only for important events
LOGGER.fine("Starting error explanation for build " + buildNumber);
LOGGER.fine("Sending request to AI provider");
LOGGER.info("Using FOLDER-LEVEL AI provider: " + providerName + ", Model: " + model);
```

**Best Practices**:
- Use `FINE`, `FINER`, or `FINEST` for debugging/tracing
- Use `INFO` only for significant operational events
- Use `WARNING` only when action may be needed
- Avoid duplicate logging (e.g., logging to both Jenkins logs and build console)
- Allow admins to create specific loggers for the plugin if needed

### ❌ DON'T: Use printStackTrace()

```java
// BAD: Prints stack trace to stderr
try {
    // code
} catch (Exception e) {
    e.printStackTrace();
}
```

### ✅ DO: Use Logger

```java
// GOOD: Proper exception logging
try {
    // code
} catch (Exception e) {
    LOGGER.log(Level.WARNING, "Failed to explain error", e);
}
```

---

## Security

### API Keys and Secrets

#### ❌ DON'T: Store Secrets as Strings

```java
// BAD: Plain text storage
private String apiKey;

public String getApiKey() {
    return apiKey;
}
```

#### ✅ DO: Use Secret Class

```java
// GOOD: Using Jenkins Secret class
private Secret apiKey;

public Secret getApiKey() {
    return apiKey;
}

public void setApiKey(Secret apiKey) {
    this.apiKey = apiKey;
}

// Access the plain text value only when needed
String plainApiKey = Secret.toString(apiKey);
```

**Validation**:
```java
// Check if Secret is not null or blank
if (!Secret.toString(config.getApiKey()).isBlank()) {
    // Safe to use
}
```

### Permission Checks

#### ❌ DON'T: Missing Permission Checks

```java
// BAD: No permission check on sensitive operation
public FormValidation doTestConnection(@QueryParameter String apiKey) {
    // Test connection logic
}
```

#### ✅ DO: Add Required Permissions

```java
// GOOD: Require appropriate permission
@RequirePOST
public FormValidation doTestConnection(@QueryParameter String apiKey) {
    Jenkins.get().checkPermission(Jenkins.ADMINISTER);
    // Test connection logic
}
```

**Note**: Use `@RequirePOST` for all state-changing or security-sensitive operations.

---

## Code Architecture

### Configuration Classes

#### ✅ DO: Use Proper Annotations

```java
@Extension
@Symbol("explainError")  // Important for CasC - makes it clear which plugin is configured
public class GlobalConfigurationImpl extends GlobalConfiguration {
    
    @DataBoundConstructor
    public GlobalConfigurationImpl() {
        // No-args constructor
        load();
    }
    
    // No need for constructor with parameters
    // Use @DataBoundSetter for optional parameters instead
    
    @DataBoundSetter
    public void setAiProvider(BaseAIProvider provider) {
        this.aiProvider = provider;
        save();
    }
    
    public static GlobalConfigurationImpl get() {
        return GlobalConfiguration.all().get(GlobalConfigurationImpl.class);
    }
}
```

**Key Points**:
- Add `@Symbol` annotation to identify the plugin in CasC YAML
- Use no-args `@DataBoundConstructor`
- Use `@DataBoundSetter` for optional/mutable fields
- Provide static `get()` method for easy access
- Don't extend unnecessary parent classes

### Extensibility with Describable

#### ✅ DO: Use ExtensionPoint for Providers

```java
public abstract class BaseAIProvider extends AbstractDescribableImpl<BaseAIProvider> 
        implements ExtensionPoint {
    
    public abstract String explainError(String log) throws ExplanationException;
    
    public abstract FormValidation validateConfiguration();
    
    public String getProviderName() {
        return getDescriptor().getDisplayName();
    }
    
    public abstract static class BaseProviderDescriptor extends Descriptor<BaseAIProvider> {
        
        public abstract String getDefaultModel();
        
        @Override
        public abstract String getDisplayName();
    }
}
```

**Benefits**:
- Allows providers to be implemented in separate plugins
- Provider-specific validation (e.g., API key mandatory for OpenAI, URL mandatory for Ollama)
- Simpler code without enum-based switching
- Easy to add new providers without modifying core plugin

### Action Management

#### ❌ DON'T: Create Duplicate Actions

```java
// BAD: Can create multiple actions for same run
public void addExplanation(Run<?, ?> run, String explanation) {
    run.addAction(new ErrorExplanationAction(explanation));
}
```

#### ✅ DO: Replace Existing Actions

```java
// GOOD: Use addOrReplaceAction
public void addExplanation(Run<?, ?> run, String explanation) {
    run.addOrReplaceAction(new ErrorExplanationAction(explanation));
}
```

### TransientActionFactory Pattern

#### ❌ DON'T: Use RunListener for UI Injection

```java
// BAD: Only works for new runs after plugin installation
@Extension
public class ErrorExplanationRunListener extends RunListener<Run<?, ?>> {
    @Override
    public void onFinalized(Run<?, ?> run) {
        run.addAction(new ConsoleExplainErrorAction(run));
    }
}
```

#### ✅ DO: Use TransientActionFactory

```java
// GOOD: Works for all runs, including existing ones
@Extension
public class ConsoleExplainErrorActionFactory extends TransientActionFactory<Run> {
    
    @Override
    public Class<Run> type() {
        return Run.class;
    }
    
    @NonNull
    @Override
    public Collection<? extends Action> createFor(@NonNull Run target) {
        return Collections.singleton(new ConsoleExplainErrorAction(target));
    }
}
```

**Benefits**: Actions are available for all runs, not just new ones.

---

## UI/UX Standards

### Use Jenkins Design Library

#### ❌ DON'T: Create Custom UI Components

```jelly
<!-- BAD: Custom styling that breaks with themes -->
<div style="background-color: white; color: black; padding: 10px;">
    ${content}
</div>
```

#### ✅ DO: Use Jenkins Design Patterns

```jelly
<!-- GOOD: Use Jenkins design library classes -->
<l:card title="Error Summary" class="jenkins-!-margin-top-3">
    <div class="jenkins-!-margin-bottom-2">
        ${it.errorSummary}
    </div>
</l:card>

<!-- Use standard buttons -->
<a href="${url}" class="jenkins-button jenkins-!-destructive-color">
    View Error Log
</a>
```

**Resources**: https://weekly.ci.jenkins.io/design-library/

**Key Points**:
- Use `l:card` for card layout
- Use `jenkins-button` class for buttons
- Use spacing classes like `jenkins-!-margin-top-3`
- Avoid hard-coded colors (breaks dark theme)
- Use design library notification system

### Icons and Symbols

#### ❌ DON'T: Reference Images Directly

```java
// BAD: Direct image reference
@Override
public String getIconFileName() {
    return "/plugin/explain-error/images/icon.png";
}
```

#### ✅ DO: Use Symbol Library

```java
// GOOD: Use Jenkins symbols
@Override
public String getIconFileName() {
    return "symbol-help";  // or other appropriate symbol
}
```

**Resources**: https://weekly.ci.jenkins.io/design-library/symbols/

### Dark Theme Compatibility

#### ❌ DON'T: Hard-code Colors

```css
/* BAD: Breaks in dark mode */
.error-explanation {
    background-color: #ffffff;
    color: #000000;
}
```

#### ✅ DO: Use CSS Variables

```css
/* GOOD: Uses theme-aware variables */
.error-explanation {
    background-color: var(--background);
    color: var(--text-color);
}
```

---

## Configuration as Code (CasC)

### Symbol Annotation

#### ✅ DO: Add @Symbol for CasC Support

```java
@Extension
@Symbol("explainError")  // CRITICAL for CasC
public class GlobalConfigurationImpl extends GlobalConfiguration {
    // ...
}

@Extension
@Symbol("openai")
public static class Descriptor extends BaseProviderDescriptor {
    // ...
}
```

**Without `@Symbol`**: Would appear as `globalConfigurationImpl` in YAML - impossible to identify the plugin.

**With `@Symbol`**: Clear and recognizable:
```yaml
unclassified:
  explainError:
    aiProvider:
      openai:
        apiKey: "${AI_API_KEY}"
        model: "gpt-4"
```

### Backward Compatibility

#### ✅ DO: Support Migration from Old Configs

```java
@Extension
@Symbol("explainError")
public class GlobalConfigurationImpl extends GlobalConfiguration {
    
    private BaseAIProvider aiProvider;
    
    // Old enum-based field kept for migration
    @Deprecated
    private transient AIProvider provider;
    
    // Migration logic
    protected Object readResolve() {
        if (provider != null && aiProvider == null) {
            // Migrate from old enum-based config
            aiProvider = convertFromEnum(provider);
            provider = null;  // Clear old value
        }
        return this;
    }
}
```

---

## Error Handling

### Exception Handling Strategy

#### ❌ DON'T: Return Error Strings

```java
// BAD: Returns error as string
public String explainError(String log) {
    try {
        return aiService.analyze(log);
    } catch (Exception e) {
        return "Error: " + e.getMessage();
    }
}
```

#### ✅ DO: Throw Custom Exceptions

```java
// GOOD: Proper exception hierarchy
public class ExplanationException extends Exception {
    public ExplanationException(String message) {
        super(message);
    }
    
    public ExplanationException(String message, Throwable cause) {
        super(message, cause);
    }
}

public String explainError(String log) throws ExplanationException {
    try {
        return aiService.analyze(log);
    } catch (IOException e) {
        throw new ExplanationException("Failed to connect to AI provider", e);
    }
}
```

**Benefits**:
- Clear separation between success and failure
- Proper error propagation
- Better debugging with stack traces
- Consistent error handling across the codebase

---

## Dependency Management

### Use Plugin BOM

#### ❌ DON'T: Specify Versions for BOM-Managed Dependencies

```xml
<!-- BAD: Version conflicts with BOM -->
<dependency>
    <groupId>org.jenkins-ci.plugins.workflow</groupId>
    <artifactId>workflow-step-api</artifactId>
    <version>2.24</version>
</dependency>
```

#### ✅ DO: Let BOM Manage Versions

```xml
<!-- GOOD: Version managed by BOM (keep in sync with pom.xml) -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.jenkins.tools.bom</groupId>
            <artifactId>bom-${jenkins.baseline}.x</artifactId>
            <version><!-- Use the same BOM version as defined in the root pom.xml --></version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.jenkins-ci.plugins.workflow</groupId>
        <artifactId>workflow-step-api</artifactId>
        <!-- No version specified -->
    </dependency>
</dependencies>
```

### Use API Plugins for Common Libraries

#### ❌ DON'T: Bundle Common Libraries

```xml
<!-- BAD: Bundles library, causes conflicts -->
<dependency>
    <groupId>org.apache.httpcomponents</groupId>
    <artifactId>httpclient</artifactId>
    <version>4.5.13</version>
</dependency>

<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.14.1</version>
</dependency>
```

#### ✅ DO: Use API Plugins

```xml
<!-- GOOD: Uses Jenkins API plugins -->
<dependency>
    <groupId>org.jenkins-ci.plugins</groupId>
    <artifactId>apache-httpcomponents-client-4-api</artifactId>
</dependency>

<dependency>
    <groupId>org.jenkins-ci.plugins</groupId>
    <artifactId>jackson2-api</artifactId>
</dependency>

<dependency>
    <groupId>org.jenkins-ci.plugins</groupId>
    <artifactId>commons-lang3-api</artifactId>
</dependency>
```

**Important**: Never use `commons-lang` (old version), always use `commons-lang3-api`.

### SLF4J and Jackson Exclusions

When using libraries like LangChain4j that bundle conflicting dependencies:

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
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

---

## Testing Requirements

### Test Coverage

#### ✅ DO: Test Success and Failure Scenarios

```java
@Test
public void testSuccessfulExplanation() throws Exception {
    // Test happy path
}

@Test
public void testFailureHandling() throws Exception {
    // Test error scenarios
}

@Test
public void testInvalidConfiguration() throws Exception {
    // Test validation
}
```

### Configuration Migration Tests

#### ✅ DO: Test Backward Compatibility

```java
@Test
public void testMigrationFromEnumConfig() throws Exception {
    // Load old XML format
    GlobalConfigurationImpl config = new GlobalConfigurationImpl();
    // ... set old enum-based fields ...
    
    // Trigger readResolve
    Object resolved = config.readResolve();
    
    // Verify migration to new format
    assertNotNull(config.getAiProvider());
    assertTrue(config.getAiProvider().getDescriptor() instanceof OpenAIProvider.DescriptorImpl);
}
```

### CasC Tests

```java
@Test
public void testConfigurationAsCode() throws Exception {
    ConfigurationAsCode.get().configure(
        getClass().getResource("casc-config.yml").toString()
    );
    
    GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
    assertNotNull(config.getAiProvider());
    // Verify configuration loaded correctly
}
```

---

## Jelly and Frontend

### Content Security Policy (CSP) Compliance

#### ❌ DON'T: Use Inline JavaScript

```jelly
<!-- BAD: Inline JavaScript violates CSP -->
<script>
function doSomething() {
    alert('Hello');
}
</script>

<button onclick="doSomething()">Click Me</button>
```

#### ✅ DO: Use External JavaScript Files

```jelly
<!-- GOOD: External JavaScript -->
<st:adjunct includes="io.jenkins.plugins.explain_error.explain-error-footer"/>

<!-- In separate .js file -->
<!-- src/main/webapp/js/explain-error-footer.js -->
```

**Resources**: https://www.jenkins.io/doc/developer/security/csp/

### Modern JavaScript

#### ❌ DON'T: Use XMLHttpRequest

```javascript
// BAD: Outdated API
var xhr = new XMLHttpRequest();
xhr.open('POST', url, true);
xhr.onreadystatechange = function() {
    if (xhr.readyState === 4) {
        // handle response
    }
};
xhr.send(data);
```

#### ✅ DO: Use Fetch API

```javascript
// GOOD: Modern fetch API with crumb support
fetch(url, {
    method: "POST",
    headers: crumb.wrap({
        "Content-Type": "application/x-www-form-urlencoded",
    }),
    body: formData
}).then(response => {
    if (!response.ok) {
        throw new Error('Request failed');
    }
    return response.text();
}).then(data => {
    // handle success
}).catch(error => {
    // handle error
});
```

### Root Context Handling

#### ✅ DO: Handle Non-Root Context

```javascript
// GOOD: Works with custom context paths (e.g., mvn hpi:run)
const rootURL = document.querySelector('head').getAttribute('data-rooturl') || '';
const url = rootURL + '/job/' + jobName + '/action';
```

#### ❌ DON'T: Assume Root Context

```javascript
// BAD: Breaks when Jenkins runs at /jenkins context
const url = '/job/' + jobName + '/action';
```

### Logging in JavaScript

#### ❌ DON'T: Excessive Console Logging

```javascript
// BAD: Too much logging
console.log('Starting...');
console.log('Step 1 complete');
console.log('Step 2 complete');
```

#### ✅ DO: Minimal Production Logging

```javascript
// GOOD: Only log important events or errors
console.error('Failed to fetch explanation:', error);
```

---

## Additional Best Practices

### Pipeline Step Configuration

#### ❌ DON'T: Create Multiple Constructors

```java
// BAD: Multiple constructors for optional parameters
public class ExplainErrorStep extends Step {
    @DataBoundConstructor
    public ExplainErrorStep() {}
    
    public ExplainErrorStep(int maxLines, int logLines) {
        this.maxLines = maxLines;
        this.logLines = logLines;
    }
}
```

#### ✅ DO: Use DataBoundSetter

```java
// GOOD: Single constructor with setters
public class ExplainErrorStep extends Step {
    @DataBoundConstructor
    public ExplainErrorStep() {}
    
    @DataBoundSetter
    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }
    
    @DataBoundSetter
    public void setLogLines(int logLines) {
        this.logLines = logLines;
    }
}
```

**Benefits**:
- Works with snippet generator
- Can create `config.jelly` for UI
- Supports help files for parameters

### Avoid Unnecessary Utility Classes

#### ❌ DON'T: Create Wrapper Classes

```java
// BAD: Unnecessary wrapper
public class ExplainErrorPlugin {
    @Extension
    public static class GlobalConfigurationImpl extends GlobalConfiguration {
        // ...
    }
}
```

#### ✅ DO: Use Direct Extension

```java
// GOOD: Direct extension class
@Extension
@Symbol("explainError")
public class GlobalConfigurationImpl extends GlobalConfiguration {
    // ...
}
```

### Documentation

#### ✅ DO: Keep README Updated

- Document all pipeline step parameters
- Include configuration examples
- Add screenshots of UI changes
- Document CasC YAML format
- Provide troubleshooting guide

### License Declaration

#### ✅ DO: Specify License in POM

```xml
<licenses>
    <license>
        <name>MIT License</name>
        <url>https://opensource.org/licenses/MIT</url>
    </license>
</licenses>
```

### Remove Developers Section

#### ❌ DON'T: Include Developers in POM

```xml
<!-- BAD: Information fetched from GitHub -->
<developers>
    <developer>
        <id>username</id>
        <name>Full Name</name>
    </developer>
</developers>
```

**Why**: This information is automatically fetched from the repository-permissions-updater.

---

## Review Checklist

Use this checklist when reviewing code:

- [ ] Logging uses appropriate levels (FINE for debug, INFO for important events only)
- [ ] No `printStackTrace()` calls
- [ ] Secrets use `Secret` class
- [ ] Permission checks on all sensitive operations (`@RequirePOST`, `Jenkins.ADMINISTER`)
- [ ] `@Symbol` annotation on all descriptors for CasC
- [ ] `@DataBoundConstructor` on configuration classes (no-args preferred)
- [ ] `@DataBoundSetter` for optional/mutable fields
- [ ] Exception handling uses custom exceptions, not error strings
- [ ] UI components use Jenkins design library
- [ ] Dark theme compatibility (no hard-coded colors)
- [ ] No inline JavaScript (CSP compliant)
- [ ] Fetch API used instead of XMLHttpRequest
- [ ] Handles non-root context paths
- [ ] Dependencies use BOM for version management
- [ ] API plugins used for common libraries
- [ ] Tests cover success and failure scenarios
- [ ] Migration tests for configuration changes
- [ ] README documentation is complete and accurate
- [ ] License specified in POM
- [ ] No developers section in POM

---

## References

- [Jenkins Plugin Tutorial](https://www.jenkins.io/doc/developer/tutorial/)
- [Jenkins Design Library](https://weekly.ci.jenkins.io/design-library/)
- [Jenkins Security Best Practices](https://www.jenkins.io/doc/developer/security/)
- [Configuration as Code Plugin](https://github.com/jenkinsci/configuration-as-code-plugin)
- [Content Security Policy](https://www.jenkins.io/doc/developer/security/csp/)
- [Plugin Development Guide](https://www.jenkins.io/doc/developer/plugin-development/)

---

## Getting Help

If you're unsure about any of these guidelines:

1. Check the [Jenkins Developer Mailing List](https://www.jenkins.io/mailing-lists/)
2. Ask in the [Jenkins Developer Chat](https://www.jenkins.io/chat/)
3. Review similar plugins in the Jenkins organization
4. Consult the [Jenkins Plugin Parent POM](https://github.com/jenkinsci/plugin-pom) documentation

---

**Last Updated**: February 2026  
**Maintainer**: @shenxianpeng

