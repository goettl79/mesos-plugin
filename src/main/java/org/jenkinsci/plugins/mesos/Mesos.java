/*
 * Copyright 2013 Twitter, Inc. and other contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import jenkins.model.Jenkins;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo;
import org.apache.mesos.Scheduler;

import java.util.*;

public abstract class Mesos {
  private static Map<MesosCloud, Mesos> clouds = new HashMap<MesosCloud, Mesos>();

  public static class JenkinsSlave {
    String name;
    String hostName;
    String label;
    int numExecutors;
    private final List<DockerInfo.PortMapping> actualPortMappings;

    public JenkinsSlave(String name, String hostName, List<DockerInfo.PortMapping> actualPortMappings, String label, int numExecutors) {
      this.name = name;
      this.hostName = hostName;

      if (actualPortMappings == null) {
          this.actualPortMappings = Collections.emptyList();
      } else {
          this.actualPortMappings = actualPortMappings;
      }

      this.numExecutors = numExecutors;
      this.label = label;
    }

    public JenkinsSlave(String name) {
        this(name, null, null, null, 1);
      }

    public JenkinsSlave(String name, String label, int numExecutors) {
      this(name, null, null, label, numExecutors);
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

    public List<DockerInfo.PortMapping> getActualPortMappings() {
        return Collections.unmodifiableList(actualPortMappings);
    }

    @Override
    public String toString() {
      return name;
    }

  }

  public static class SlaveRequest {
    JenkinsSlave slave;
    final double cpus;
    final int mem;
    final MesosSlaveInfo slaveInfo;

    public SlaveRequest(JenkinsSlave slave, double cpus, int mem, MesosSlaveInfo slaveInfo) {
      this.slave = slave;
      this.cpus = cpus;
      this.mem = mem;
      this.slaveInfo = slaveInfo;
    }

    public String[] getExternalContainerOptions() {
      if (this.slaveInfo.getExternalContainerInfo().getOptions().trim().isEmpty()) {
        return new String[0];
      } else {
        return this.slaveInfo.getExternalContainerInfo().getOptions().trim().split(",");
      }
    }
  }


  interface SlaveResult {
    void running(JenkinsSlave slave);

    void finished(JenkinsSlave slave);

    void failed(JenkinsSlave slave);
  }

  abstract public void startScheduler(String jenkinsMaster, MesosCloud mesosCloud);
  abstract public void updateScheduler(String jenkinsMaster, MesosCloud mesosCloud);
  abstract public boolean isSchedulerRunning();
  abstract public void stopScheduler();
  abstract public Scheduler getScheduler();
  /**
   * Starts a jenkins slave asynchronously in the mesos cluster.
   *
   * @param request
   *          slave request.
   * @param result
   *          this callback will be called when the slave starts.
   */
  abstract public void startJenkinsSlave(SlaveRequest request, SlaveResult result);


  /**
   * Stop a jenkins slave asynchronously in the mesos cluster.
   *
   * @param name
   *          jenkins slave.
   *
   */
  abstract public void stopJenkinsSlave(String name);

  /**
   * @return the mesos implementation instance for the cloud instances (since there might be more than one
   */
  public static synchronized Mesos getInstance(MesosCloud key) {
    if (!clouds.containsKey(key)) {
      clouds.put(key, new MesosImpl());
    }
    return clouds.get(key);
  }

  public static Collection<Mesos> getAllClouds() {
    return clouds.values();
  }


  /**
   * When Jenkins configuration is saved, teardown any active scheduler whose cloud has been removed.
   */
  @Extension
  public static class GarbageCollectorImpl extends SaveableListener {

    @Override
    public void onChange(Saveable o, XmlFile file) {
      if (o instanceof Jenkins) {
        Jenkins j = (Jenkins) o;
        for (Iterator<Map.Entry<MesosCloud, Mesos>> it = clouds.entrySet().iterator(); it.hasNext();) {
          Map.Entry<MesosCloud, Mesos> entry = it.next();
          if (!j.clouds.contains(entry.getKey())) {
            entry.getValue().stopScheduler();
            it.remove();
          }
        }
      }
    }
  }
}