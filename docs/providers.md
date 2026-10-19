# AI Providers

Explain Error works with twelve providers. Pick one under **Manage Jenkins → System → Explain Error Plugin
Configuration → AI Provider**, or in a folder's configuration, fill in its fields and click **Test Configuration**.
Each provider pre-fills a default model, which you can replace with any model the provider offers.

Every provider below also has a [Configuration as Code](https://plugins.jenkins.io/configuration-as-code/) example.
Settings that apply to all providers, such as `language` or `customContext`, go at the `explainError` level, not
inside the provider block; see [Configuration](configuration.md#configuration-as-code).

| Provider | CasC symbol | Key | Endpoint |
|---|---|---|---|
| [Anthropic (Claude)](#anthropic-claude) | `anthropic` | API key or Credentials ID | Optional |
| [AWS Bedrock](#aws-bedrock) | `bedrock` | AWS credential chain | Optional (VPC endpoint) |
| [Azure OpenAI](#azure-openai) | `azureOpenai` | Credentials ID | Required |
| [Custom Okta AI](#custom-okta-ai) | `customOkta` | Okta client credentials | Required |
| [DeepSeek](#deepseek) | `deepseek` | API key | Optional |
| [Google Gemini](#google-gemini) | `gemini` | API key | Optional |
| [LangGraph Platform](#langgraph-platform) | `langGraph` | API key | Required |
| [Microsoft Foundry](#microsoft-foundry) | `microsoftFoundry` | API key | Required |
| [Ollama](#ollama) | `ollama` | Optional | Required |
| [OpenAI](#openai) | `openai` | API key | Optional |
| [OpenAI Compatible](#openai-compatible) | `openaiCompatible` | Optional | Required |
| [Qwen](#qwen) | `qwen` | API key or Credentials ID | Optional |

Keep keys out of the YAML file itself: reference an environment variable or a secret source as in the examples, or
store the key as a Jenkins Secret text credential where the provider takes a Credentials ID.

## Anthropic (Claude)

- **Models**: `claude-sonnet-4-6` (default), `claude-opus-4-7`, `claude-haiku-4-5-20251001`, the Claude 5 models, and
  any other model of your account
- **Key**: an API key from the [Anthropic Console](https://console.anthropic.com/settings/keys), or the ID of a Secret
  text credential, which wins over the key when both are set
- **Endpoint**: leave empty for the Anthropic API, or give the base URL of a Claude-compatible service
- **Temperature**: Anthropic accepts 0 to 1, so a higher setting is lowered to 1; when no temperature is set, 0.3 is
  sent. Claude Opus 4.7 and newer models reject the parameter, and it is left out for them (see
  [model deprecations](https://platform.claude.com/docs/en/about-claude/model-deprecations))

```yaml
unclassified:
  explainError:
    aiProvider:
      anthropic:
        apiKey: "${ANTHROPIC_API_KEY}"
        model: "claude-sonnet-4-6"
        # url: ""                          # Optional, empty for the Anthropic API
        # maxTokens: 4096                  # Optional, defaults to 4096
        # credentialsId: "anthropic-api-key" # Alternative to apiKey: a Secret text credential
    enableExplanation: true
```

## AWS Bedrock

- **Requires**: the [AWS SDK 2.x](https://plugins.jenkins.io/aws-java-sdk2/) plugin (`aws-java-sdk2-core`). The
  dependency is optional, so that installations that do not use Bedrock do not carry the AWS SDK; **AWS Bedrock
  appears in the AI Provider list only after you install that plugin** under **Manage Jenkins → Plugins → Available
  plugins**
- **Models**: a Bedrock model ID or inference profile, such as `us.anthropic.claude-sonnet-4-5-20250929-v1:0`,
  `eu.anthropic.claude-sonnet-4-5-20250929-v1:0` or `us.amazon.nova-lite-v1:0`; copy the exact ID from the Bedrock
  console of your region
- **Credentials**: no API key; the AWS credential chain is used (instance profile, environment variables, and so on)
- **Region**: optional, AWS SDK region resolution when empty. The form suggests `eu-west-1`
- **Endpoint**: optional Bedrock Runtime endpoint for VPC endpoints or private AWS-compatible endpoints; host-only
  values default to HTTPS
- **Cross-account role**: optional IAM role ARN. Jenkins calls STS AssumeRole with its base AWS credentials and invokes
  Bedrock with the temporary credentials
- **Guardrail**: optional [Amazon Bedrock guardrail](https://docs.aws.amazon.com/bedrock/latest/userguide/guardrails.html).
  Set both **Guardrail Identifier** (ID or ARN) and **Guardrail Version** (a number or `DRAFT`); a configuration with
  only one of them is rejected rather than run unguarded. The guardrail evaluates the build log sent to the model and
  the model's answer. When it blocks either, no explanation is produced and its blocked message is reported as the
  error; an answer it only masked (for example PII) is shown as returned. The AWS credentials need
  `bedrock:ApplyGuardrail` on the guardrail in addition to `bedrock:InvokeModel`

```yaml
unclassified:
  explainError:
    aiProvider:
      bedrock:
        model: "us.anthropic.claude-sonnet-4-5-20250929-v1:0"
        region: "us-east-1" # Optional, AWS SDK region resolution when empty
        # url: "vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com" # Optional private endpoint
        # roleArn: "arn:aws:iam::123456789012:role/JenkinsBedrockInvokeRole"         # Optional cross-account role
        # guardrailIdentifier: "arn:aws:bedrock:us-east-1:123456789012:guardrail/abc123" # Optional guardrail
        # guardrailVersion: "1" # Required with guardrailIdentifier: a version number or DRAFT
    enableExplanation: true
```

## Azure OpenAI

- **Models**: any Azure OpenAI deployment that supports Chat Completions (`/chat/completions`) or the Responses API
  (`/openai/v1/responses`)
- **Key**: the ID of a Secret text credential holding the Azure OpenAI API key
- **Endpoint**: the resource endpoint, such as `https://my-resource.openai.azure.com`
- **API Type**: `Chat Completions API` (default) or `Responses API`, for newer models such as gpt-5.x that do not
  support Chat Completions
- **API Version**: used by the Chat Completions API. The Responses API uses the v1 endpoint, but the field is still
  required

```yaml
unclassified:
  explainError:
    aiProvider:
      azureOpenai:
        endpoint: "https://my-resource.openai.azure.com"
        deployment: "gpt-4o"           # Azure OpenAI deployment name
        apiVersion: "2025-01-01-preview"
        credentialsId: "azure-openai-key" # Secret text credential
        # apiType: "RESPONSES"         # Responses API for newer models; defaults to Chat Completions
    enableExplanation: true
```

## Custom Okta AI

For company AI gateways that require an OAuth client-credentials exchange with Okta before the chat call.

- **Models**: any model your gateway exposes
- **Authentication**: Okta client credentials (`clientId` and `clientSecret`) exchanged at the token URL
- **Token URL**: required, and separate from the chat endpoint
- **Chat endpoint**: required; templates such as `.../deployments/{model}/chat/completions` are supported
- **App key**: optional `appKey` and `userId` fill the OpenAI-style `user` metadata for gateways that require an
  application key
- **Access token header**: configurable name and prefix, so the same provider supports `Authorization: Bearer ...`,
  `api-key: ...` and similar patterns

```yaml
unclassified:
  explainError:
    aiProvider:
      customOkta:
        url: "https://chat-ai.example.com/openai/deployments/{model}/chat/completions" # Required
        tokenUrl: "https://id.example.com/oauth2/default/v1/token"                     # Required
        model: "gpt-5-nano"                                                            # Required
        clientId: "${OKTA_CLIENT_ID}"                                                  # Required
        clientSecret: "${OKTA_CLIENT_SECRET}"                                          # Required
        scope: "custom.scope"                                                          # Optional
        apiVersion: "2025-04-01-preview"                                               # Optional
        accessTokenHeader: "api-key"   # Optional, defaults to Authorization
        accessTokenPrefix: ""          # Optional, defaults to empty: the raw token is sent
        appKey: "${CUSTOM_AI_APP_KEY}" # Optional
        userId: "svc-jenkins"          # Optional
        timeoutSeconds: 180            # Optional, defaults to 180
    enableExplanation: true
```

## DeepSeek

- **Models**: `deepseek-v4-flash` (default), `deepseek-v4-pro`
- **Key**: from the [DeepSeek Platform](https://platform.deepseek.com/)
- **Endpoint**: defaults to `https://api.deepseek.com`; set another for a DeepSeek-compatible service

```yaml
unclassified:
  explainError:
    aiProvider:
      deepseek:
        apiKey: "${DEEPSEEK_API_KEY}"
        model: "deepseek-v4-flash"
        # url: "https://api.deepseek.com" # Optional, the official endpoint by default
    enableExplanation: true
```

## Google Gemini

- **Models**: `gemini-2.5-flash`, `gemini-2.5-pro`, and other Gemini models
- **Key**: from [Google AI Studio](https://aistudio.google.com/app/apikey)
- **Endpoint**: leave empty for the Google AI API, or give the URL of a Gemini-compatible service

```yaml
unclassified:
  explainError:
    aiProvider:
      gemini:
        apiKey: "${GEMINI_API_KEY}"
        model: "gemini-2.5-flash"
        # url: "" # Optional, empty for the Google AI API
    enableExplanation: true
```

## LangGraph Platform

For custom agents deployed on the LangGraph Platform that expose a `/runs/wait` endpoint.

- **Model**: the assistant UUID or registered graph name to invoke, such as `my-agent`
- **Key**: a LangGraph Platform API key, sent as the `x-api-key` header
- **Endpoint**: required; the base URL of your deployment, such as `https://your-deployment.langchain.com`
- **Timeout**: 180 seconds; `timeoutSeconds` can only be set through Configuration as Code, and saving the form
  resets it

```yaml
unclassified:
  explainError:
    aiProvider:
      langGraph:
        url: "https://your-langgraph-deployment.example.com"
        model: "my-agent" # Assistant UUID or graph name
        apiKey: "${LANGGRAPH_API_KEY}"
        # timeoutSeconds: 180 # Optional, defaults to 180
    enableExplanation: true
```

## Microsoft Foundry

- **Models**: any chat completions deployment of your Foundry resource, such as Azure OpenAI, DeepSeek, Grok or Mistral
  models
- **Key**: the endpoint key of your Foundry resource
- **Endpoint**: required; the resource endpoint, such as `https://my-resource.services.ai.azure.com`, or the full
  OpenAI v1 base URL, such as `https://my-resource.openai.azure.com/openai/v1`

```yaml
unclassified:
  explainError:
    aiProvider:
      microsoftFoundry:
        apiKey: "${MICROSOFT_FOUNDRY_API_KEY}"
        model: "gpt-4o" # Foundry model deployment name
        url: "https://my-resource.services.ai.azure.com" # /openai/v1 is appended automatically
    enableExplanation: true
```

## Ollama

For private, local or open-source models, with no external API use or cost.

- **Models**: `gemma3:1b` (default), `gpt-oss`, `deepseek-r1`, or any model pulled into your Ollama instance
- **Key**: not needed unless your Ollama server is secured
- **Endpoint**: required, such as `http://localhost:11434`

```yaml
unclassified:
  explainError:
    aiProvider:
      ollama:
        model: "gemma3:1b"
        url: "http://localhost:11434"
    enableExplanation: true
```

## OpenAI

- **Models**: `gpt-5`, `gpt-5-mini`, `gpt-4.1` (default), `gpt-4.1-mini`
- **Key**: from the [OpenAI Platform](https://platform.openai.com/settings)
- **Endpoint**: leave empty for the OpenAI API, or give the URL of an OpenAI-compatible service

```yaml
unclassified:
  explainError:
    aiProvider:
      openai:
        apiKey: "${OPENAI_API_KEY}"
        model: "gpt-5-mini"
        # url: "" # Optional, empty for the OpenAI API
    enableExplanation: true
```

## OpenAI Compatible

For AI gateways, proxies and self-hosted platforms such as LiteLLM or OpenWebUI.

- **Models**: any model name your gateway exposes, such as `gpt-4o`, `azure/gpt-4o`, `claude-sonnet-4-6` or
  `llama-3.1-70b`
- **Key**: optional; sent as an `Authorization: Bearer` header. Leave it empty for unauthenticated gateways
- **Endpoint**: required; the gateway's base URL, such as `https://litellm.example.com` (LiteLLM) or
  `https://openwebui.example.com/api/v1` (OpenWebUI). `/chat/completions` is appended, so include the gateway's API
  path prefix, such as `/v1`, if it has one
- **API Type**: `Chat Completions API` (default) or `Responses API` for gateways and models that only support the
  Responses API. Requests then go to `<url>/responses` with `store: false`, so the provider does not keep the build
  logs
- **Redirects**: HTTP redirects, such as `http` to `https` behind a load balancer, are followed

```yaml
unclassified:
  explainError:
    aiProvider:
      openaiCompatible:
        url: "https://litellm.example.com" # Required: base URL of your gateway
        model: "azure/gpt-4o"              # Any model name the gateway exposes
        # apiKey: "${GATEWAY_API_KEY}"     # Optional, sent as a Bearer token
        # apiType: RESPONSES               # Optional: CHAT_COMPLETIONS (default) or RESPONSES
    enableExplanation: true
```

## Qwen

- **Models**: `qwen-plus` (default), `qwen-flash`, `qwen3-max`, and other Model Studio models
- **Key**: an API key from Alibaba Cloud Model Studio (DashScope), or the ID of a Secret text credential, which wins
  over the key when both are set
- **Endpoint**: defaults to the China (Beijing) endpoint `https://dashscope.aliyuncs.com/compatible-mode/v1`; set the
  Singapore, US or Hong Kong endpoint for those regions

```yaml
unclassified:
  explainError:
    aiProvider:
      qwen:
        apiKey: "${DASHSCOPE_API_KEY}"
        model: "qwen-plus"
        # url: "https://dashscope.aliyuncs.com/compatible-mode/v1" # Optional, China (Beijing) by default
        # credentialsId: "qwen-api-key" # Alternative to apiKey: a Secret text credential
    enableExplanation: true
```
