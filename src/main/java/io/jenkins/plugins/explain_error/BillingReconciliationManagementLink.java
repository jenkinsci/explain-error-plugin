package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.ManagementLink;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Jenkins management page for Explain Error billing reconciliation.
 *
 * <p>Accessible at {@code /manage/explain-error-billing/} from the Jenkins Manage page.
 * Shows cumulative provider call counts so administrators can compare them against
 * their AI provider billing statements.</p>
 */
@Extension
@Symbol("explainErrorBilling")
public class BillingReconciliationManagementLink extends ManagementLink {

    @Override
    public String getIconFileName() {
        return "symbol-bar-chart-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "Explain Error — Billing Reconciliation";
    }

    @Override
    public String getUrlName() {
        return "explain-error-billing";
    }

    @Override
    public String getDescription() {
        return "Cumulative provider call statistics for reconciling AI usage against billing statements.";
    }

    @Override
    public Category getCategory() {
        return Category.STATUS;
    }

    /**
     * Exposes the success call map to the Jelly view.
     */
    public Map<String, Long> getSuccessCallsByProvider() {
        BillingReconciliationRecorder recorder = BillingReconciliationRecorder.get();
        if (recorder == null) {
            return Map.of();
        }
        return recorder.getSuccessCallsByProvider();
    }

    /**
     * Exposes the fallback call map to the Jelly view.
     */
    public Map<String, Long> getFallbackCallsByProvider() {
        BillingReconciliationRecorder recorder = BillingReconciliationRecorder.get();
        if (recorder == null) {
            return Map.of();
        }
        return recorder.getFallbackCallsByProvider();
    }

    /**
     * Returns total quota-rejected calls.
     */
    public long getQuotaRejectedTotal() {
        BillingReconciliationRecorder recorder = BillingReconciliationRecorder.get();
        return recorder != null ? recorder.getQuotaRejectedTotal() : 0L;
    }

    /**
     * Handles the reset action (POST only, requires ADMINISTER).
     */
    @RequirePOST
    public void doReset(StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        BillingReconciliationRecorder recorder = BillingReconciliationRecorder.get();
        if (recorder != null) {
            recorder.resetStats();
        }
        rsp.sendRedirect2(".");
    }
}
