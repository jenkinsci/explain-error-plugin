package io.jenkins.plugins.explain_error;

import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleNote;
import hudson.model.Run;
import jenkins.model.Jenkins;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.logging.Logger;
import java.util.List;

/**
 * Utility for extracting log lines related to a failing build or pipeline step
 * and computing a URL that points back to the error source.
 * <p>
 * For {@link org.jenkinsci.plugins.workflow.job.WorkflowRun} (Pipeline) builds,
 * this class walks the flow graph to locate the node that originally threw the
 * error, reads a limited number of log lines from that step, and records a
 * node-specific URL that can be used to navigate to the failure location.
 * When no failing step log can be found, or when the build is not a pipeline,
 * it falls back to the standard build console log.
 * <p>
 * If the optional {@code pipeline-graph-view} plugin is installed, the
 * generated URL is compatible with its overview page so that consumers can
 * deep-link directly into the failing node from error explanations.
 */
public class PipelineLogExtractor {

    private static final Logger LOGGER = Logger.getLogger(PipelineLogExtractor.class.getName());
    public static final String URL_NAME = "stages";
    private boolean isGraphViewPluginAvailable = false;
    private transient String url;
    private transient Run<?, ?> run;
    private int maxLines;



    /**
     * Reads the provided log text and returns at most the last {@code maxLines} lines.
     * <p>
     * The entire log is streamed into memory, Jenkins {@link ConsoleNote} annotations are stripped,
     * and a sliding window is maintained over the lines: when the number of buffered lines reaches
     * {@code maxLines}, the oldest line is removed before adding the next one. This ensures that
     * only the most recent {@code maxLines} lines are retained.
     * <p>
     * Line terminators ({@code \n} and {@code \r}) are removed from each returned line. If no log
     * content is available or an error occurs while reading, an empty list is returned.
     *
     * @param logText  the annotated log text associated with a {@link FlowNode}
     * @param maxLines the maximum number of trailing log lines to return
     * @return a list containing up to the last {@code maxLines} lines of the log, or an empty list
     *         if the log is empty or an error occurs
     */
    private List<String> readLimitedLog(AnnotatedLargeText<? extends FlowNode> logText,
                                               int maxLines) {
        StringWriter writer = new StringWriter();
        try {
            long offset = logText.writeLogTo(0, writer);
            if (offset <= 0) {
                return Collections.emptyList();
            }
            String cleanLog = ConsoleNote.removeNotes(writer.toString());
            BufferedReader reader = new BufferedReader(new StringReader(cleanLog));
            LinkedList<String> queue = new LinkedList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (queue.size() >= maxLines) {
                    queue.removeFirst();
                }

                queue.add(line);
            }
            return new ArrayList<>(queue);
        } catch (IOException e) {
            LOGGER.severe("Unable to serialize the flow node log: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Extracts the log output of the specific step that caused the pipeline failure.
     *
     * @return A non-null list of log lines for the failed step, or the overall build log if
     *         no failed step with a log is found.
     * @throws IOException if there is an error reading the build logs.
     */
    public List<String> getFailedStepLog() throws IOException {

        if (this.run instanceof WorkflowRun) {
            FlowExecution execution = ((WorkflowRun) this.run).getExecution();
            if (execution == null) {
                setUrl("0");
                return run.getLog(maxLines);
            }

            FlowGraphWalker walker = new FlowGraphWalker(execution);
            for (FlowNode node : walker) {
                ErrorAction errorAction = node.getAction(ErrorAction.class);
                if (errorAction != null) {
                    FlowNode nodeThatThrewException = ErrorAction.findOrigin(errorAction.getError(), execution);
                    if (nodeThatThrewException == null) {
                        continue;
                    }
                    LogAction logAction = nodeThatThrewException.getAction(LogAction.class);
                    if (logAction != null) {
                        AnnotatedLargeText<? extends FlowNode> logText = logAction.getLogText();
                        List<String> result = readLimitedLog(logText, this.maxLines);
                        if (result == null || result.isEmpty())
                        {
                            continue;
                        }
                        setUrl(nodeThatThrewException.getId());
                        return result;
                   }
                }
            }
        }
        /* Reference to pipeline overview or console output */
        setUrl("0");
        return run.getLog(maxLines);
    }

    private void setUrl(String node)
    {
        String rootUrl = Jenkins.get().getRootUrl();
        if (isGraphViewPluginAvailable) {
            url = rootUrl + run.getUrl() + URL_NAME + "?selected-node=" + node;
        } else {
            url = rootUrl + run.getUrl() + "console";
        }
    }

    /**
     * Returns the URL associated with the extracted log.
     * <p>
     * When {@link #getFailedStepLog()} finds a failed pipeline step with an attached log and the
     * {@code pipeline-graph-view} plugin is available, this will point to the pipeline overview page with the
     * failing node preselected. Otherwise, it falls back to the build's console output URL.
     * </p>
     *
     * @return the Jenkins URL for either the pipeline overview of the failing step or the build console output,
     *         or {@code null} if {@link #getFailedStepLog()} has not been invoked successfully.
     */
    public String getUrl() {
        return this.url;
    }

    public PipelineLogExtractor(Run<?, ?> run, int maxLines)
    {
        this.run = run;
        this.maxLines = maxLines;
        if (Jenkins.get().getPlugin("pipeline-graph-view") != null) {
            isGraphViewPluginAvailable = true;
        }
    }
}
