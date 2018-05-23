package org.jenkinsci.plugins.mesos.actions;

import hudson.model.InvisibleAction;
import hudson.model.Job;
import hudson.model.Run;

import javax.annotation.Nonnull;

public class MesosBuiltOnProjectAction extends InvisibleAction {

    private final Job<?, ?> job;

    public MesosBuiltOnProjectAction(@Nonnull Job<?, ?> job) {
        this.job = job;
    }

    /*
    * this tackles the fact that we might not have a a MesosBuiltOnAction in job.lastBuild yet.
    * We believe (have faith!) that this is only the case with concurrent builds which in turn should not use
    * node-affinity in conjunction with (persistent) WS mounts. (we could make scheduler-type configurable though)
    *
    * as this code assumes we're using node affinity everywhere, lastCompletedBuild should (in most cases) contain the
    * same (and thus _probably_ correct) builtOn information.
    *
    */
    private MesosBuiltOnAction getFirstActionOrNull(@Nonnull Run... runs) {
        for (Run run : runs) {
            if (run != null) {
                MesosBuiltOnAction action = run.getAction(MesosBuiltOnAction.class);
                if (action != null) {
                    return action;
                }
            }
        }
        // log that no action was found
        return null;
    }

    public MesosBuiltOnAction getAction() {
        // TODO: better logic to determine last built with MesosBuiltOnAction, b/c might not be contained in lastBuild()
        return getFirstActionOrNull(job.getLastBuild(), job.getLastCompletedBuild());
    }

}
