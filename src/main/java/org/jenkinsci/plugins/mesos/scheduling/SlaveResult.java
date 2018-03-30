package org.jenkinsci.plugins.mesos.scheduling;

import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.monitoring.MesosTaskFailureMonitor;

import java.util.logging.Level;
import java.util.logging.Logger;

public class SlaveResult {

    private static final Logger LOGGER = Logger.getLogger(SlaveResult.class.getName());

    private transient MesosCloud cloud;

    public enum FAILED_CAUSE {
        MESOS_CLOUD_REPORTED_TASK_FAILED("The MesosCloud reported the task as failed"),
        MESOS_CLOUD_REPORTED_TASK_LOST("The MesosCloud reported the task as lost"),
        MESOS_CLOUD_REPORTED_TASK_ERROR("The MesosCloud reported the task as erroneous "),
        RESOURCE_LIMIT_REACHED("The maximum count of CPUs or Memory for your framework is reached"),
        SLAVE_NEVER_SCHEDULED("The slave never came online");

        private String text;

        FAILED_CAUSE(String s) {
            text = s;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    public SlaveResult(MesosCloud cloud) {
        this.cloud = cloud;
    }

    public void running(JenkinsSlave.ResultJenkinsSlave slave) {
        // do nothing
    }

    public void finished(JenkinsSlave.ResultJenkinsSlave slave) {
        LOGGER.info(String.format("Remove finished Node %s from Jenkins", slave.getName()));
        cloud.removeSlaveFromJenkins(slave);
    }

    public void failed(JenkinsSlave.ResultJenkinsSlave slave, SlaveResult.FAILED_CAUSE cause) {
        try {
            MesosTaskFailureMonitor.getInstance().addFailedSlave(slave, cause);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error while getting MesosTaskFailureMonitor", e);
        }
    }
}
