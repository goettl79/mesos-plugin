package org.jenkinsci.plugins.mesos.scheduling;


public class Result {
    private final SlaveResult slaveResult;
    private final JenkinsSlave jenkinsSlave;

    public Result(SlaveResult slaveResult, JenkinsSlave jenkinsSlave) {
        this.slaveResult = slaveResult;
        this.jenkinsSlave = jenkinsSlave;
    }

    public SlaveResult getResult() {
        return slaveResult;
    }

    public JenkinsSlave getSlave() {
        return jenkinsSlave;
    }
}
