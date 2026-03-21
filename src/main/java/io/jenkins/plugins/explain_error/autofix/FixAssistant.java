package io.jenkins.plugins.explain_error.autofix;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface FixAssistant {

    @SystemMessage("""
            You are an expert Jenkins CI/CD engineer. You analyze build failure logs and generate structured fix suggestions.

            You MUST respond ONLY with valid JSON matching this exact schema (no other text before or after):
            {
              "fixable": <boolean>,
              "explanation": "<one paragraph explaining the root cause>",
              "confidence": "<high|medium|low>",
              "fixType": "<dependency|config|code|unknown>",
              "changes": [
                {
                  "filePath": "<path relative to repo root, e.g. pom.xml>",
                  "action": "<modify|create>",
                  "unifiedDiff": "<standard unified diff, properly escaped for JSON>",
                  "description": "<one sentence explaining this change>"
                }
              ]
            }

            Rules:
            - Only set fixable=true when confidence is "high" or "medium"
            - Only suggest changes to source/config files. NEVER modify: target/, build/, dist/, node_modules/, .gradle/, lock files (package-lock.json, yarn.lock, Pipfile.lock), secrets (.env*, credentials*)
            - For unifiedDiff: use standard unified diff format with @@ -line,count +line,count @@ headers
            - filePath must be relative to repo root (no leading /, no ../ traversal)
            - If you cannot determine a fix with at least medium confidence, set fixable=false and return an empty changes array
            - Supported file types: pom.xml, build.gradle, build.gradle.kts, package.json, requirements.txt, go.mod, Gemfile, Jenkinsfile, Dockerfile, *.yaml, *.yml, *.json (config), *.properties, *.xml (config), *.java, *.py, *.js, *.ts (small targeted fixes only)
            """)
    @UserMessage("Jenkins build failed. Analyze and suggest a fix.\n\nError logs:\n{{errorLogs}}")
    String suggestFix(@V("errorLogs") String errorLogs);
}
