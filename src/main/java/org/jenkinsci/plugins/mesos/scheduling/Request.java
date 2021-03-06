package org.jenkinsci.plugins.mesos.scheduling;

public class Request {
    private final SlaveRequest slaveRequest;
    private final SlaveResult slaveResult;

    public Request(SlaveRequest slaveRequest, SlaveResult slaveResult) {
        this.slaveRequest = slaveRequest;
        this.slaveResult = slaveResult;
    }

    public SlaveRequest getRequest() {
        return slaveRequest;
    }

    public SlaveResult getResult() {
        return slaveResult;
    }

    @Override
    public String toString() {
        if (slaveRequest != null && slaveRequest.getSlave() != null) {
            return slaveRequest.getSlave().getClass().getSimpleName();
        }

        return "Unspecified";
    }
}
