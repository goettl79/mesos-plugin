package org.jenkinsci.plugins.mesos.scheduling;

import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;

public class SlaveRequest {
    private final JenkinsSlave jenkinsSlave;
    private final double cpus;
    private final int mem;
    private final String role;
    private final MesosSlaveInfo slaveInfo;

    public SlaveRequest(JenkinsSlave jenkinsSlave, double cpus, int mem, String role,
                        MesosSlaveInfo slaveInfo) {
        this.jenkinsSlave = jenkinsSlave;
        this.cpus = cpus;
        this.mem = mem;
        this.role = role;
        this.slaveInfo = slaveInfo;
    }

    public JenkinsSlave getSlave() {
        return jenkinsSlave;
    }

    public double getCpus() {
        return cpus;
    }

    public int getMem() {
        return mem;
    }

    public String getRole() {
        return role;
    }

    public MesosSlaveInfo getSlaveInfo() {
        return slaveInfo;
    }
}
