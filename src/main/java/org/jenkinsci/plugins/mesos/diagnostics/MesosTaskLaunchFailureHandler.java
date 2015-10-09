package org.jenkinsci.plugins.mesos.diagnostics;


import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.JenkinsScheduler;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosSlaveInfo;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Extension
public class MesosTaskLaunchFailureHandler extends AsyncPeriodicWork {

    private static final long RECURRENCE_PERIOD_MINUTES = 1;

    private static Map<MesosCloud, List<JenkinsScheduler.Result>> FAILED_RESULTS_MAP = new HashMap<MesosCloud, List<JenkinsScheduler.Result>>();
    private static Jenkins JENKINS = Jenkins.getInstance();

    public MesosTaskLaunchFailureHandler() {
        super("Mesos Failed Task Handler");
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();
        logger.println("Started Mesos Failed Task Handler");

        for(MesosCloud mesosCloud : FAILED_RESULTS_MAP.keySet()) {
            List<JenkinsScheduler.Result> results = FAILED_RESULTS_MAP.get(mesosCloud);
            List<JenkinsScheduler.Result> resultsToRemove = new ArrayList<JenkinsScheduler.Result>();
            if(results != null) {
                for (JenkinsScheduler.Result result : results) {
                    MesosSlaveInfo mesosSlaveInfo = mesosCloud.getSlaveInfo(result.getSlave().getLabel());

                    if (mesosSlaveInfo != null) {
                        logger.println("rerequest for failed task "+result.getSlave().getName());
                        mesosCloud.requestNodes(JENKINS.getLabel(mesosSlaveInfo.getLabelString()), result.getSlave().getNumExecutors());
                        resultsToRemove.add(result);
                    }
                }
            }
            results.removeAll(resultsToRemove);
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit2.MINUTES.toMillis(RECURRENCE_PERIOD_MINUTES);
    }

    public static void addFailure(MesosCloud mesosCloud, JenkinsScheduler.Result result) {
        List<JenkinsScheduler.Result> list = FAILED_RESULTS_MAP.get(mesosCloud);
        if(list == null) {
            list = new ArrayList<JenkinsScheduler.Result>();
            FAILED_RESULTS_MAP.put(mesosCloud, list);
        }

        list.add(result);
    }
}
