package io.jenkins.plugins.explain_error;

import hudson.model.Run;
import java.util.LinkedHashMap;
import java.util.Map;
import jenkins.model.RunAction2;

/**
 * Hidden build action storing AI explanations generated for individual Pipeline graph nodes.
 */
public class StepErrorExplanationAction implements RunAction2 {

    private final Map<String, Entry> explanationsByNodeId = new LinkedHashMap<>();
    private transient Run<?, ?> run;

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    public Run<?, ?> getRun() {
        return run;
    }

    public Entry getExplanation(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        return explanationsByNodeId.get(nodeId);
    }

    public void putExplanation(String nodeId, ErrorExplanationAction action) {
        explanationsByNodeId.put(nodeId, new Entry(
                nodeId,
                action.getExplanation(),
                action.getUrlString(),
                action.getProviderName(),
                action.getProviderModel(),
                action.getInputLogLineCount(),
                action.getTimestamp()));
    }

    public boolean hasExplanation(String nodeId) {
        Entry entry = getExplanation(nodeId);
        return entry != null && entry.hasValidExplanation();
    }

    public static final class Entry {
        private final String nodeId;
        private final String explanation;
        private final String urlString;
        private final String providerName;
        private final String providerModel;
        private final int inputLogLineCount;
        private final long timestamp;

        Entry(String nodeId, String explanation, String urlString, String providerName,
              String providerModel, int inputLogLineCount, long timestamp) {
            this.nodeId = nodeId;
            this.explanation = explanation;
            this.urlString = urlString;
            this.providerName = providerName;
            this.providerModel = providerModel;
            this.inputLogLineCount = Math.max(0, inputLogLineCount);
            this.timestamp = timestamp;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getExplanation() {
            return explanation;
        }

        public String getUrlString() {
            return urlString;
        }

        public String getProviderName() {
            return providerName;
        }

        public String getProviderModel() {
            return providerModel;
        }

        public int getInputLogLineCount() {
            return inputLogLineCount;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean hasValidExplanation() {
            return explanation != null && !explanation.isBlank();
        }
    }
}
