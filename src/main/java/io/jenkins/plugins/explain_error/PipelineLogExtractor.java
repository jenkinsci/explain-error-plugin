package io.jenkins.plugins.explain_error;

import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import edu.umd.cs.findbugs.annotations.NonNull;

import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleNote;
import hudson.model.Run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class PipelineLogExtractor {

    private static List<String> readLimitedLog(AnnotatedLargeText<? extends FlowNode> logText,
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
                line = line.replace("\n", "").replace("\r", "");
                queue.add(line);
            }
            return new ArrayList<>(queue);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    /**
     * Extracts the log output of the specific step that caused the pipeline failure.
     * @param run The pipeline build
     * @return The log text of the failed step, or null if no failed step with a log is found.
     * @throws IOException
     */
    public static List<String> getFailedStepLog(@NonNull  Run<?, ?> run, int maxLines) throws IOException {

        if (run instanceof WorkflowRun) {
            FlowExecution execution = ((WorkflowRun) run).getExecution();

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
                        List<String> result = readLimitedLog(logText, maxLines);
                        if (result == null || result.isEmpty())
                        {
                            continue;
                        }
                        return result;
                   }
                }
            }
        }

        return run.getLog(maxLines);
    }
}
