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

    public MesosBuiltOnAction getAction() {
        // TODO: better logic to determine last built with MesosBuiltOnAction, b/c might not be contained in lastBuild()
        Run<?, ?> build = job.getLastBuild();

        if (build == null) {
            return null;
        }

        MesosBuiltOnAction action = build.getAction(MesosBuiltOnAction.class);
        return action;
    }

}
