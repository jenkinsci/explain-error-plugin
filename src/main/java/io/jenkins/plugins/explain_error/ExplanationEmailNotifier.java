package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Cause;
import hudson.model.Run;
import hudson.model.User;
import hudson.tasks.MailAddressResolver;
import hudson.tasks.Mailer;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.lang3.StringUtils;

/**
 * Sends the generated AI error explanation by email.
 *
 * <p>Recipients are the user who triggered the build (if any) plus the fixed
 * recipients configured globally. Email addresses of Jenkins users are resolved
 * via {@link MailAddressResolver}. The SMTP server, credentials, charset and
 * sender address are taken from Jenkins' own mail infrastructure (the Mailer
 * plugin's global configuration), so no separate SMTP setup is required.
 *
 * <p>All failures are caught and logged; sending an email must never interfere
 * with the build lifecycle.
 */
class ExplanationEmailNotifier {

    private static final Logger LOGGER = Logger.getLogger(ExplanationEmailNotifier.class.getName());

    /**
     * Builds and sends the explanation email for the given run.
     *
     * @param run    the failed build the explanation belongs to
     * @param action the AI explanation action attached to the build
     */
    void sendExplanationEmail(@NonNull Run<?, ?> run, @NonNull ErrorExplanationAction action) {
        if (!action.hasValidExplanation()) {
            LOGGER.fine("[" + fullName(run) + "] No valid explanation to email.");
            return;
        }

        Set<InternetAddress> recipients = collectRecipients(run);
        if (recipients.isEmpty()) {
            LOGGER.fine("[" + fullName(run) + "] No recipients with resolvable email addresses; skipping email.");
            return;
        }

        Mailer.DescriptorImpl mailer = Mailer.descriptor();
        if (mailer == null) {
            LOGGER.fine("[" + fullName(run) + "] Mailer plugin is not available; skipping email.");
            return;
        }
        String fromAddress = resolveFromAddress();
        if (StringUtils.isBlank(fromAddress)) {
            LOGGER.fine("[" + fullName(run)
                    + "] No sender address configured in Jenkins (System Admin e-mail address / Mailer);"
                    + " skipping email.");
            return;
        }

        String charset = mailer.getCharset();
        try {
            Session session = mailer.createSession();
            MimeMessage message = new MimeMessage(session);
            message.setFrom(Mailer.stringToAddress(fromAddress, charset));
            message.setRecipients(Message.RecipientType.TO,
                    recipients.toArray(new InternetAddress[0]));
            message.setSubject(buildSubject(run), charset);
            message.setText(buildBody(run, action), charset);
            message.setSentDate(new Date());

            Transport.send(message);
            LOGGER.fine("[" + fullName(run) + "] Explanation email sent to " + recipients.size() + " recipient(s).");
        } catch (MessagingException | UnsupportedEncodingException e) {
            LOGGER.log(Level.WARNING, "[" + fullName(run) + "] Failed to send explanation email.", e);
        }
    }

    /**
     * Resolves the sender address from Jenkins' own configuration, falling back
     * to the Mailer reply-to address when the global admin address is unset.
     */
    @CheckForNull
    private String resolveFromAddress() {
        JenkinsLocationConfiguration location = JenkinsLocationConfiguration.get();
        if (location != null && StringUtils.isNotBlank(location.getAdminAddress())) {
            return location.getAdminAddress();
        }
        Mailer.DescriptorImpl mailer = Mailer.descriptor();
        return mailer != null ? mailer.getReplyToAddress() : null;
    }

    /**
     * Collects the recipient addresses: the triggering user plus the fixed
     * recipients configured in the global configuration.
     */
    Set<InternetAddress> collectRecipients(@NonNull Run<?, ?> run) {
        String charset = "UTF-8";
        Set<InternetAddress> addresses = new LinkedHashSet<>();

        User starter = triggeringUser(run);
        if (starter != null) {
            String address = MailAddressResolver.resolve(starter);
            if (StringUtils.isBlank(address)) {
                LOGGER.fine("No email address could be resolved for triggering user " + starter.getId() + ".");
            } else {
                addAddress(addresses, address, charset);
            }
        }

        for (String address : parseRecipients(GlobalConfigurationImpl.get().getEmailRecipients())) {
            addAddress(addresses, address, charset);
        }

        return addresses;
    }

    private void addAddress(Set<InternetAddress> addresses, String address, String charset) {
        try {
            addresses.add(Mailer.stringToAddress(address, charset));
        } catch (AddressException | UnsupportedEncodingException e) {
            LOGGER.fine("Invalid email address '" + address + "': " + e.getMessage());
        }
    }

    private Set<String> parseRecipients(@CheckForNull String recipients) {
        Set<String> result = new LinkedHashSet<>();
        if (StringUtils.isBlank(recipients)) {
            return result;
        }
        for (String token : recipients.split("[,;\\s]+")) {
            if (StringUtils.isNotBlank(token)) {
                result.add(token.trim());
            }
        }
        return result;
    }

    @CheckForNull
    private User triggeringUser(@NonNull Run<?, ?> run) {
        Cause.UserIdCause cause = run.getCause(Cause.UserIdCause.class);
        if (cause == null || StringUtils.isBlank(cause.getUserId())) {
            return null;
        }
        return User.getById(cause.getUserId(), false);
    }

    private String buildSubject(@NonNull Run<?, ?> run) {
        return "AI error explanation: " + run.getParent().getFullName() + " #" + run.getNumber() + " failed";
    }

    private String buildBody(@NonNull Run<?, ?> run, @NonNull ErrorExplanationAction action) {
        StringBuilder body = new StringBuilder();
        body.append("The build ")
                .append(run.getParent().getFullName())
                .append(" #")
                .append(run.getNumber())
                .append(" failed.\n\n");

        String buildUrl = run.getAbsoluteUrl();
        if (StringUtils.isNotBlank(buildUrl)) {
            body.append("Build: ").append(buildUrl).append("\n");
            body.append("Explanation: ").append(buildUrl).append(action.getUrlName()).append("/\n");
        }
        body.append("Provider: ").append(action.getProviderName());
        if (StringUtils.isNotBlank(action.getProviderModel())) {
            body.append(" (").append(action.getProviderModel()).append(")");
        }
        body.append("\n\n");
        body.append("AI error explanation:\n");
        body.append("----------------------------------------\n");
        body.append(action.getExplanation()).append("\n");

        return body.toString();
    }

    private static String fullName(@NonNull Run<?, ?> run) {
        return run.getParent().getFullName() + " #" + run.getNumber();
    }
}
