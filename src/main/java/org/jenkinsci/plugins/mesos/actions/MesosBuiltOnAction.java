package org.jenkinsci.plugins.mesos.actions;

import hudson.model.InvisibleAction;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean
public class MesosBuiltOnAction extends InvisibleAction {

    private final String mesosAgentHostname;
    private final String jenkinsAgentHostname;
    private final String containerId;

    public MesosBuiltOnAction(String mesosAgentHostname, String jenkinsAgentHostname, String containerId) {
        this.mesosAgentHostname = mesosAgentHostname;
        this.jenkinsAgentHostname = jenkinsAgentHostname;
        this.containerId = containerId;
    }

    @Exported(visibility = 1)
    public String getMesosAgentHostname() {
        return mesosAgentHostname;
    }

    @Exported(visibility = 1)
    public String getJenkinsAgentHostname() {
        return jenkinsAgentHostname;
    }

    @Exported(visibility = 1)
    public String getContainerId() {
        return containerId;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) { return false; }
        if (obj == this) { return true; }
        if (obj.getClass() != getClass()) {
            return false;
        }
        MesosBuiltOnAction rhs = (MesosBuiltOnAction) obj;
        return new EqualsBuilder()
                .append(mesosAgentHostname, rhs.mesosAgentHostname)
                .append(jenkinsAgentHostname, rhs.jenkinsAgentHostname)
                .append(containerId, rhs.containerId)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 53)
                .append(mesosAgentHostname)
                .append(jenkinsAgentHostname)
                .append(containerId)
                .toHashCode();
    }
}
