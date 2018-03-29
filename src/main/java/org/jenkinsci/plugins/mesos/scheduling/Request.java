package org.jenkinsci.plugins.mesos.scheduling;

import com.google.common.annotations.VisibleForTesting;

@VisibleForTesting
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
}
