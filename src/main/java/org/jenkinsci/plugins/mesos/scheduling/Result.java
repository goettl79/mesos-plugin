package org.jenkinsci.plugins.mesos.scheduling;


public class Result {
    private final SlaveResult slaveResult;
    private final JenkinsSlave.ResultJenkinsSlave jenkinsSlave;

    public Result(SlaveResult slaveResult, JenkinsSlave.ResultJenkinsSlave jenkinsSlave) {
        this.slaveResult = slaveResult;
        this.jenkinsSlave = jenkinsSlave;
    }

    public SlaveResult getResult() {
        return slaveResult;
    }

    public JenkinsSlave.ResultJenkinsSlave getSlave() {
        return jenkinsSlave;
    }
}
