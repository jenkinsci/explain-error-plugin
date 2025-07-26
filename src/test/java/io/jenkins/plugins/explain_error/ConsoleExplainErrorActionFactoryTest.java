package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Action;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ConsoleExplainErrorActionFactoryTest {

    @Test
    void testFactoryBasicFunctionality(JenkinsRule jenkins) throws Exception {
        ConsoleExplainErrorActionFactory factory = new ConsoleExplainErrorActionFactory();
        
        // Test factory creation
        assertNotNull(factory);
        
        // Test type method
        Class<Run<?, ?>> type = factory.type();
        assertNotNull(type);
        assertEquals(Run.class, type);
        
        // Test creating actions for a build
        FreeStyleProject project = jenkins.createFreeStyleProject("test");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        
        Collection<? extends Action> actions = factory.createFor(build);
        assertNotNull(actions);
        assertEquals(1, actions.size());
        
        Action action = actions.iterator().next();
        assertTrue(action instanceof ConsoleExplainErrorAction);
        
        ConsoleExplainErrorAction consoleAction = (ConsoleExplainErrorAction) action;
        assertEquals(build, consoleAction.getRun());
    }

    @Test
    void testFactoryConsistency(JenkinsRule jenkins) throws Exception {
        ConsoleExplainErrorActionFactory factory = new ConsoleExplainErrorActionFactory();
        
        FreeStyleProject project = jenkins.createFreeStyleProject("test");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        
        // Test multiple calls return consistent results
        Collection<? extends Action> actions1 = factory.createFor(build);
        Collection<? extends Action> actions2 = factory.createFor(build);
        
        assertNotNull(actions1);
        assertNotNull(actions2);
        assertEquals(actions1.size(), actions2.size());
        assertEquals(1, actions1.size());
        assertEquals(1, actions2.size());
    }
}
