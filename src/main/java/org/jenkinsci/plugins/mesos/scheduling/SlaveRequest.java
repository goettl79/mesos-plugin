package org.jenkinsci.plugins.mesos.scheduling;

import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;

public class SlaveRequest {
    private final JenkinsSlave.RequestJenkinsSlave jenkinsSlave;
    private final MesosSlaveInfo slaveInfo;

    public SlaveRequest(JenkinsSlave.RequestJenkinsSlave jenkinsSlave, MesosSlaveInfo slaveInfo) {
        this.jenkinsSlave = jenkinsSlave;
        this.slaveInfo = slaveInfo;
    }

    public JenkinsSlave.RequestJenkinsSlave getSlave() {
        return jenkinsSlave;
    }

    public MesosSlaveInfo getSlaveInfo() {
        return slaveInfo;
    }
}
