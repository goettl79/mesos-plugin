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
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Slave;
import hudson.model.queue.CauseOfBlockage;
import hudson.remoting.VirtualChannel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.mesos.Protos;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MesosSlave extends Slave implements EphemeralNode {

  public static final long serialVersionUID = 42;

  private final transient MesosCloud cloud;
  private final transient MesosSlaveInfo slaveInfo;

  private Protos.TaskStatus taskStatus;
  private boolean pendingDelete;
  private String linkedItem;
  private String dockerContainerID;

  private static final Logger LOGGER = Logger.getLogger(MesosSlave.class
      .getName());

  public MesosSlave(MesosCloud cloud, String name, int numExecutors, MesosSlaveInfo slaveInfo, String linkedItem) throws IOException, FormException {
    super(name,
          slaveInfo.getLabelString(), // node description.
          StringUtils.isBlank(slaveInfo.getRemoteFSRoot()) ? "jenkins" : slaveInfo.getRemoteFSRoot().trim(),   // remoteFS.
          "" + numExecutors,
          slaveInfo.getMode(),
          slaveInfo.getLabelString(), // Label.
          new MesosComputerLauncher(cloud, name),
          new MesosRetentionStrategy(slaveInfo.getIdleTerminationMinutes(), slaveInfo.getMaximumTimeToLiveMinutes()),
          Collections.<NodeProperty<?>> emptyList());
    this.cloud = cloud;
    this.slaveInfo = slaveInfo;
    this.linkedItem = linkedItem;

    LOGGER.info("Constructing Mesos slave '" + name + "' for item '" + linkedItem + "' using cloud '" + cloud.getDescription() + "'");
  }

  public MesosCloud getCloud() {
    return this.cloud;
  }

  public MesosSlaveInfo getSlaveInfo() {
    return slaveInfo;
  }
 
  public int getIdleTerminationMinutes() {
    return slaveInfo.getIdleTerminationMinutes();
  }

  public int getMaximumTimeToLiveMinutes() {
    return slaveInfo.getMaximumTimeToLiveMinutes();
  }

  public Mesos getMesosInstance() {
      return Mesos.getInstance(cloud);
  }

  public void terminate() {
    LOGGER.info("Terminating slave " + getNodeName());
    try {
      ComputerLauncher launcher = getLauncher();
      // If this is a mesos computer launcher, terminate the launcher.

      VirtualChannel channel = this.getChannel();
      if (channel != null) {
        if (launcher instanceof MesosComputerLauncher) {
          ((MesosComputerLauncher) launcher).terminate();
        }
        channel.close();
      }

      Jenkins.getInstance().removeNode(this);

    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Failed to terminate Mesos instance: "
          + getInstanceId(), e);
    }
  }

  @Override
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl) super.getDescriptor();
  }


  @Override
  public Node asNode() {
    return this;
  }

  @Extension
  public static class DescriptorImpl extends SlaveDescriptor {
    @Override
    public String getDisplayName() {
      return "Mesos Slave";
    }

    /**
     * We only create this kind of nodes programatically.
     */
    @Override
    public boolean isInstantiable() {
      return false;
    }
  }

  @Override
  public CauseOfBlockage canTake(Queue.BuildableItem item) {
    CauseOfBlockage causeOfBlockage = super.canTake(item);

    if(causeOfBlockage != null) {
      return causeOfBlockage;
    }

    if(linkedItem != null) {
        String fullItemName = cloud.getFullNameOfItem(item);
        if (!linkedItem.equals(fullItemName)) {
          return CauseOfBlockage.fromMessage(Messages._MesosSlave_IsReservedForAnOtherItem(fullItemName));
        }
    }

    return null;
  }

  private String getInstanceId() {
    return getNodeName();
  }

  public boolean isPendingDelete() {
      return pendingDelete;
  }

  public void setPendingDelete(boolean pendingDelete) {
      this.pendingDelete = pendingDelete;
  }

  public void idleTimeout() {
    LOGGER.info("Mesos instance idle time expired: " + getInstanceId() + ", terminate now");
    terminate();
  }

  public Protos.TaskStatus getTaskStatus() {
    return taskStatus;
  }

  public void setTaskStatus(Protos.TaskStatus taskStatus) {
    this.taskStatus = taskStatus;
  }

  public String getDockerContainerID() {
    return dockerContainerID;
  }

  public void setDockerContainerID(String dockerContainerID) {
    this.dockerContainerID = dockerContainerID;
  }

  public String getMonitoringURL() {
    String containerId = getDockerContainerID();
    if(cloud.getGrafanaDashboardURL() == null || cloud.getGrafanaDashboardURL().isEmpty() || containerId == null) {
      return  null;
    }

    String host = cloud.getGrafanaDashboardURL();

    long from = this.getComputer().getConnectTime();
    long to = System.currentTimeMillis();

    return String.format("%s?var-slave=%s&var-container=%s&from=%d&to=%d", host, this.getDisplayName(), containerId, from, to);
  }

  public String getLinkedItem() {
    return linkedItem;
  }

  public void setLinkedItem(String linkedItem) {
    this.linkedItem = linkedItem;
  }

  @Override
  public Computer createComputer() {
    return new MesosComputer(this);
  }

  @Override
  public FilePath getRootPath() {
    FilePath rootPath = createPath(remoteFS);
    if (rootPath != null) {
      try {
        // Construct absolute path for slave's remote file system root.
        rootPath = rootPath.absolutize();
      } catch (IOException e) {
        LOGGER.warning("IO exception while absolutizing slave root path: " +e);
      } catch (InterruptedException e) {
        LOGGER.warning("InterruptedException while absolutizing slave root path: " +e);
      }
    }
    // Return root path even if we caught an exception,
    // let the caller handle the error.
    return rootPath;
  }
}
