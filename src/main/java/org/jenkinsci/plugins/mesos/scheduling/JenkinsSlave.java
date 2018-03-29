package org.jenkinsci.plugins.mesos.scheduling;

import org.apache.mesos.Protos;

import java.util.Collections;
import java.util.List;

public class JenkinsSlave {
    private final String name;
    private final String hostName;
    private final String label;
    private final int numExecutors;
    private final List<Protos.ContainerInfo.DockerInfo.PortMapping> actualPortMappings;
    private final String linkedItem;
    private final double cpus;
    private final int mem;


    public JenkinsSlave(String name, String hostName, List<Protos.ContainerInfo.DockerInfo.PortMapping> actualPortMappings, String label, int numExecutors, String linkedItem, double cpus, int mem) {
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
    }

    public JenkinsSlave(String name, String label, int numExecutors, String linkedItem, double cpus, int mem) {
        this(name, null, null, label, numExecutors, linkedItem, cpus, mem);
    }

    public JenkinsSlave(String name) {
        this(name, null, null, null, 0, null, 0, 0);
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

    public int getMem() {
        return mem;
    }

    @Override
    public String toString() {
        return name;
    }

}
