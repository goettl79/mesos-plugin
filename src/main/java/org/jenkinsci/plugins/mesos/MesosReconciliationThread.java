package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class MesosReconciliationThread extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(MesosReconciliationThread.class.getName());

    public MesosReconciliationThread() {
        super("Mesos Reconciliation");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN * 5;
    }

    public static void invoke() {
        getInstance().run();
    }

    private static MesosReconciliationThread getInstance() {
        return Jenkins.getInstance().getExtensionList(AsyncPeriodicWork.class).get(MesosReconciliationThread.class);
    }

    @Override
    protected void execute(TaskListener listener) {
        try {
            Jenkins jenkins = Jenkins.getInstance();
            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof MesosCloud) {
                    MesosCloud mesosCloud = (MesosCloud) cloud;
                    JenkinsScheduler jenkinsScheduler = (JenkinsScheduler) Mesos.getInstance(mesosCloud).getScheduler();
                    jenkinsScheduler.reconcileAllTasks();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error while reconciling tasks:", e);
        }
    }
}
