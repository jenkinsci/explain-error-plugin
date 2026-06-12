# Enterprise Data Protection

Explain Error sanitizes data before sending build failure context to an AI provider.

## What is protected

Log sanitization is enabled by default and replaces matched sensitive values with:

```text
[REDACTED_SECRET]
```

The default rules redact common credential assignments, bearer/basic authorization headers, GitHub and AWS-style tokens, private key blocks, internal URLs, private repository URLs, local artifact/workspace paths, and email addresses.

## Preview before sending

Enable **Preview Payload Before Sending** in global configuration to require console action users to review the sanitized payload before the provider call starts. The preview shows line count, redaction count, dropped line count, and the sanitized payload excerpt.

Pipeline steps are non-interactive, so they sanitize and audit automatically instead of prompting.

## Audit what was sent

When **Audit Sent Payload** is enabled, the build's **AI Error Explanation** action stores the sanitized log payload that was sent to the provider, along with redaction and dropped-line counts. The original raw logs are not persisted by this audit field.

Disable this option if your organization does not want sanitized payloads stored on build records.

## Allow and deny regex policies

Use **Payload Allow Regex** to send only matching payload lines. Non-matching lines are dropped before redaction.

Use **Payload Deny Regex** to redact organization-specific sensitive text that is not covered by the default rules.

Examples:

```text
Payload Allow Regex: (?i)(error|failed|exception|caused by)
Payload Deny Regex: (?i)(customer-[0-9]+|tenant-[a-z0-9-]+)
```

Invalid regular expressions are rejected on the configuration page.
