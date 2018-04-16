package org.jenkinsci.plugins.mesos.scheduling;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public abstract class JenkinsSlave {

    public static class RoleCapped extends RequestJenkinsSlave {

        public RoleCapped(String name, String label, Integer numExecutors, String linkedItem, String lastBuildHostname, Long estimatedDuration, Double cpus, Double mem, Set<MesosSlaveInfo.PortMapping> portMappings, String role) {
            super(name, label, numExecutors, linkedItem, lastBuildHostname, estimatedDuration, cpus, mem, portMappings, new LinkedHashSet<String>(Arrays.asList(SHARED_ROLE, role)));
        }

    }

    public static class RoleResourcesFirst extends RequestJenkinsSlave {

        public RoleResourcesFirst(String name, String label, Integer numExecutors, String linkedItem, String lastBuildHostname, Long estimatedDuration, Double cpus, Double mem, Set<MesosSlaveInfo.PortMapping> portMappings, String role) {
            super(name, label, numExecutors, linkedItem, lastBuildHostname, estimatedDuration, cpus, mem, portMappings, new LinkedHashSet<String>(Arrays.asList(SHARED_ROLE, role)));
        }

    }

    public static class SharedResourcesFirst extends RequestJenkinsSlave {

        public SharedResourcesFirst(String name, String label, Integer numExecutors, String linkedItem, String lastBuildHostname, Long estimatedDuration, Double cpus, Double mem, Set<MesosSlaveInfo.PortMapping> portMappings, String role) {
            super(name, label, numExecutors, linkedItem, lastBuildHostname, estimatedDuration, cpus, mem, portMappings, new LinkedHashSet<String>(Arrays.asList(SHARED_ROLE, role)));
        }

    }


    public static abstract class RequestJenkinsSlave extends JenkinsSlave {

        private final String lastBuildHostname;
        private final Long estimatedDuration;
        private final Set<MesosSlaveInfo.PortMapping> portMappings;

        public RequestJenkinsSlave(String name, String label, Integer numExecutors, String linkedItem, String lastBuildHostname, Long estimatedDuration, Double cpus, Double mem, Set<MesosSlaveInfo.PortMapping> portMappings, Set<String> roles) {
            super(name, label, numExecutors, linkedItem, cpus, mem, roles);

            this.lastBuildHostname = lastBuildHostname;
            this.portMappings = portMappings;
            this.estimatedDuration = estimatedDuration;
        }

        public String getLastBuildHostname() {
            return lastBuildHostname;
        }

        public Long getEstimatedDuration() {
            return estimatedDuration;
        }

        public Set<MesosSlaveInfo.PortMapping> getPortMappings() {
            return Collections.unmodifiableSet(portMappings);
        }

        public MesosSlaveInfo.PortMapping getPortMapping(int containerPort) {
            for (MesosSlaveInfo.PortMapping portMapping : portMappings) {
                if (portMapping.getContainerPort() == containerPort) {
                    return portMapping;
                }
            }

            return null;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("Agent request: ").append(this.getClass().getSimpleName())
                    .append(", name: ").append(getName())
                    .append(", label:").append(getLabel())
                    .append(", lastHost: ").append(lastBuildHostname)
                    .append(", estimatedDuration: ").append(estimatedDuration)
                    .toString();
        }
    }

    public static class ResultJenkinsSlave extends JenkinsSlave {

        private final Set<MesosSlaveInfo.PortMapping> actualPortMappings;
        private final String hostname;
        private final String requestJenkinsClass;

        public ResultJenkinsSlave(RequestJenkinsSlave requestJenkinsSlave) {
            this(requestJenkinsSlave, StringUtils.EMPTY, Collections.<MesosSlaveInfo.PortMapping>emptySet());
        }

        public ResultJenkinsSlave(RequestJenkinsSlave requestJenkinsSlave, String hostname, Set<MesosSlaveInfo.PortMapping> actualPortMappings) {
            super(requestJenkinsSlave.getName(),
                    requestJenkinsSlave.getLabel(),
                    requestJenkinsSlave.getNumExecutors(),
                    requestJenkinsSlave.getLinkedItem(),
                    requestJenkinsSlave.getCpus(),
                    requestJenkinsSlave.getMem(),
                    requestJenkinsSlave.getRoles());

            this.hostname = hostname;
            this.actualPortMappings = actualPortMappings;
            this.requestJenkinsClass = requestJenkinsSlave.getClass().getSimpleName();
        }

        public String getHostname() {
            return hostname;
        }

        @SuppressWarnings("unused")
        public Set<MesosSlaveInfo.PortMapping> getActualPortMappings() {
            return Collections.unmodifiableSet(actualPortMappings);
        }

        public String getRequestJenkinsClass() {
            return requestJenkinsClass;
        }
    }

    private final static String SHARED_ROLE = "*";

    private final String name;
    private final String label;
    private final Integer numExecutors;
    private final String linkedItem;
    private final Double cpus;
    private final Double mem;
    // only for backwards compatibility, rem afterwards?
    private final String mainRole;
    // preparation for supporting multi-role frameworks (e.g resource.getRole() is deprecated)
    private final Set<String> roles;

    public JenkinsSlave(String name, String label, Integer numExecutors, String linkedItem, Double cpus, Double mem, Set<String> roles) {
        this.name = name;

        this.numExecutors = numExecutors;
        this.label = label;
        this.linkedItem = linkedItem;
        this.cpus = cpus;
        this.mem = mem;

        this.mainRole = getMainRole(roles);
        this.roles = roles;
    }

    private String getMainRole(Set<String> roles) {
        for (String role: roles) {
            if (!StringUtils.equals(role, SHARED_ROLE)) {
                return role;
            }
        }

        // TODO: better message...
        throw new IllegalArgumentException("Please specify at least one role.");
    }

    public String getName() {
        return name;
    }

    public String getLabel() {
        return label;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public String getLinkedItem() {
        return linkedItem;
    }

    public double getCpus() {
        return cpus;
    }

    public double getMem() {
        return mem;
    }

    public String getRole() {
        return mainRole;
    }

    public Set<String> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    @Override
    public String toString() {
        return name;
    }

}
