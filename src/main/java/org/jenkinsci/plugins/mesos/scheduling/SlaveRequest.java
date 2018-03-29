package org.jenkinsci.plugins.mesos.scheduling;

import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;

public class SlaveRequest {
    private final JenkinsSlave jenkinsSlave;
    private final MesosSlaveInfo slaveInfo;

    public SlaveRequest(JenkinsSlave jenkinsSlave, MesosSlaveInfo slaveInfo) {
        this.jenkinsSlave = jenkinsSlave;
        this.slaveInfo = slaveInfo;
    }

    public JenkinsSlave getSlave() {
        return jenkinsSlave;
    }

    public MesosSlaveInfo getSlaveInfo() {
        return slaveInfo;
    }
}
