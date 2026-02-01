package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.service.AiServices;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

public class AzureOpenAIProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(AzureOpenAIProvider.class.getName());

    protected Secret apiKey;

    @DataBoundConstructor
    public AzureOpenAIProvider(String url, String model, Secret apiKey) {
        super(url, model);
        this.apiKey = apiKey;
    }

    public Secret getApiKey() {
        return apiKey;
    }

    @Override
    public Assistant createAssistant() {
        // For Azure, the URL is the endpoint and model is the deployment name
        ChatModel model = AzureOpenAiChatModel.builder()
                .endpoint(Util.fixEmptyAndTrim(getUrl())) // Azure endpoint is required
                .apiKey(getApiKey().getPlainText())
                .deploymentName(getModel()) // In Azure, this is the deployment name
                .temperature(0.3)
                .responseFormat(ResponseFormat.JSON)
                .strictJsonSchema(true)
                .build();

        return AiServices.create(Assistant.class, model);
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        if (listener != null) {
            if (Util.fixEmptyAndTrim(Secret.toString(getApiKey())) == null) {
                listener.getLogger().println("No Api key configured for Azure OpenAI.");
            } else if (Util.fixEmptyAndTrim(getUrl()) == null) {
                listener.getLogger().println("No Endpoint configured for Azure OpenAI.");
            } else if (Util.fixEmptyAndTrim(getModel()) == null) {
                listener.getLogger().println("No Deployment Name configured for Azure OpenAI.");
            }
        }
        return Util.fixEmptyAndTrim(Secret.toString(getApiKey())) == null ||
                Util.fixEmptyAndTrim(getUrl()) == null ||
                Util.fixEmptyAndTrim(getModel()) == null;
    }

    @Extension
    @Symbol("azureOpenai")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        // Common deployment name examples users might create in Azure OpenAI
        // Note: These are example deployment names, not model names
        private static final String[] COMMON_DEPLOYMENT_NAMES = new String[]{
                "gpt-5",
                "gpt-5-mini",
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-4-turbo",
                "gpt-35-turbo"
        };

        @NonNull
        @Override
        public String getDisplayName() {
            return "Azure OpenAI";
        }

        @Override
        public String getDefaultModel() {
            return "gpt-4.1";
        }

        @GET
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public AutoCompletionCandidates doAutoCompleteModel(@QueryParameter String value) {
            AutoCompletionCandidates c = new AutoCompletionCandidates();
            for (String model : COMMON_DEPLOYMENT_NAMES) {
                if (model.toLowerCase().startsWith(value.toLowerCase())) {
                    c.add(model);
                }
            }
            return c;
        }

        /**
         * Method to test the AI API configuration.
         * This is called when the "Test Configuration" button is clicked.
         */
        @POST
        public FormValidation doTestConfiguration(@QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model) throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            AzureOpenAIProvider provider = new AzureOpenAIProvider(url, model, apiKey);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }

    }

}
