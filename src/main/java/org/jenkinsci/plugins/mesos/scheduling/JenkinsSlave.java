package org.jenkinsci.plugins.mesos.scheduling;

import org.apache.mesos.Protos;

import java.util.Collections;
import java.util.List;

public class JenkinsSlave {
    private final String name;
    private final String hostName;
    private final String label;
    private final Integer numExecutors;
    private final List<Protos.ContainerInfo.DockerInfo.PortMapping> actualPortMappings;
    private final String linkedItem;
    private final Double cpus;
    private final Double mem;
    private final String role;


    public JenkinsSlave(String name, String hostName, List<Protos.ContainerInfo.DockerInfo.PortMapping> actualPortMappings, String label, Integer numExecutors, String linkedItem, Double cpus, Double mem, String role) {
        this.name = name;
        this.hostName = hostName;

        if (actualPortMappings == null) {
            this.actualPortMappings = Collections.emptyList();
        } else {
            this.actualPortMappings = actualPortMappings;
        }

        this.numExecutors = numExecutors;
        this.label = label;
        this.linkedItem = linkedItem;
        this.cpus = cpus;
        this.mem = mem;
        this.role = role;
    }

    public JenkinsSlave(String name, String label, Integer numExecutors, String linkedItem, Double cpus, Double mem, String role) {
        this(name, null, null, label, numExecutors, linkedItem, cpus, mem, role);
    }

    public JenkinsSlave(String name, String role) {
        this(name, null, null, null, 0, null, 0.0, 0.0, role);
    }

    public String getName() {
        return name;
    }

    public String getHostName() {
        return hostName;
    }

    public String getLabel() {
        return label;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    @SuppressWarnings("unused")
    public List<Protos.ContainerInfo.DockerInfo.PortMapping> getActualPortMappings() {
        return Collections.unmodifiableList(actualPortMappings);
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
        return role;
    }

    @Override
    public String toString() {
        return name;
    }

}
