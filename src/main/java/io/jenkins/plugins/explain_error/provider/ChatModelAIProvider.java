package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import org.springframework.security.core.Authentication;

/**
 * Base class for providers whose backend is a langchain4j {@link ChatModel}.
 * <p>
 * Subclasses implement only {@link #createChatModel}: the assistant plumbing
 * lives here, and because the model factory receives the full request context
 * (credentials scope and temperature) in one signature, a provider cannot
 * accidentally drop a parameter the way the old per-provider overload copies
 * could. Providers that talk to their API directly (without a
 * {@link ChatModel}) extend {@link BaseAIProvider} instead.
 */
public abstract class ChatModelAIProvider extends BaseAIProvider {

    protected ChatModelAIProvider(String url, String model) {
        super(url, model);
    }

    /**
     * Creates the chat model for one request.
     *
     * @param item           the item defining the credentials scope, or {@code null}
     * @param authentication the authentication for credentials lookup, or {@code null}
     * @param temperature    the temperature to use, or {@code null} for the provider default
     * @return the configured chat model
     */
    protected abstract ChatModel createChatModel(@CheckForNull Item item,
                                                 @CheckForNull Authentication authentication,
                                                 @CheckForNull Double temperature);

    @Override
    public Assistant createAssistant(@CheckForNull Item item,
                                     @CheckForNull Authentication authentication,
                                     @CheckForNull Double temperature) {
        return AiServices.create(Assistant.class, createChatModel(item, authentication, temperature));
    }

    @Override
    public io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant(
            @CheckForNull Item item, @CheckForNull Authentication authentication) {
        return AiServices.create(io.jenkins.plugins.explain_error.autofix.FixAssistant.class,
                createChatModel(item, authentication, null));
    }
}
