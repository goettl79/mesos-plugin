package org.jenkinsci.plugins.mesos.actions;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;

public class MesosBuiltOnProperty extends JobProperty<Job<?, ?>> implements Serializable {

    public MesosBuiltOnProperty() {
        // for serializer
    }


    @Override
    @Restricted(NoExternalUse.class)
    public Collection<? extends Action> getJobActions(Job<?, ?> job) {
        return Collections.singletonList(new MesosBuiltOnProjectAction(job));
    }

    @Extension
    public static class MesosBuiltOnPropertyDescriptor extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "MesosBuiltOnProperty";
        }
    }
}
