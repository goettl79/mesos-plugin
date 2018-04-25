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
