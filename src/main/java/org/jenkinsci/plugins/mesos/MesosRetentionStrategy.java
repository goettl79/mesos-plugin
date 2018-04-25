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

import hudson.model.*;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.mesos.Protos;
import org.jenkinsci.plugins.mesos.actions.MesosBuiltOnAction;
import org.jenkinsci.plugins.mesos.actions.MesosBuiltOnProjectAction;
import org.jenkinsci.plugins.mesos.actions.MesosBuiltOnProperty;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.joda.time.DateTimeUtils;

import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * This is inspired by {@link hudson.slaves.CloudRetentionStrategy}.
 */
public class MesosRetentionStrategy extends RetentionStrategy<MesosComputer> implements ExecutorListener {

  /**
   * Number of minutes of idleness before an instance should be terminated. A
   * value of zero indicates that the instance should never be automatically
   * terminated.
   */
  public final int idleTerminationMinutes;
  private final int maximumTimeToLive;
  private transient ReentrantLock computerCheckLock = new ReentrantLock(false);

  private static final Logger LOGGER = Logger
      .getLogger(MesosRetentionStrategy.class.getName());

  public MesosRetentionStrategy(int idleTerminationMinutes, int maximumTimeToLive) {
    this.idleTerminationMinutes = idleTerminationMinutes;
    this.maximumTimeToLive = maximumTimeToLive;
  }

  private void readResolve() {
      computerCheckLock = new ReentrantLock(false);
  }

  @Override
  public long check(MesosComputer c) {
    if (!computerCheckLock.tryLock()) {
      return 1;
    } else {
      try {
        return checkInternal(c);
      } finally {
          computerCheckLock.unlock();
      }
    }
  }

  /**
   * Checks if the computer has expired and marks it for deletion.
   * {@link org.jenkinsci.plugins.mesos.MesosCleanupThread} will then come around and terminate those tasks
   * @param c The Mesos Computer
   * @return The number of minutes to check again afterwards
   */
  private long checkInternal(MesosComputer c) {
    MesosSlave mesosJenkinsAgent = c.getNode();
    if (mesosJenkinsAgent == null) {
      return 1;
    }

    //if an executor is "dead", determining the conntectionTime may cause an NullPointer Exception
    try {
      Protos.TaskStatus taskStatus = mesosJenkinsAgent.getTaskStatus();
      if(taskStatus != null) {
        //if task is staging, check again in a minute
        if(Protos.TaskState.TASK_STAGING.equals(taskStatus.getState())) {
          return 1;
        }
      }

      // If we just launched this computer, check back after 1 min.
      // NOTE: 'c.getConnectTime()' refers to when the Jenkins slave was launched.
      if ((DateTimeUtils.currentTimeMillis() - c.getConnectTime()) <
          MINUTES.toMillis(idleTerminationMinutes < 1 ? 1 : idleTerminationMinutes)) {
        return 1;
      }

      final long idleMilliseconds =
          DateTimeUtils.currentTimeMillis() - c.getIdleStartMilliseconds();
      // Terminate the computer if it is idle for longer than
      // 'idleTerminationMinutes'.
      if (isTerminable() && c.isIdle()) {

        if (idleMilliseconds > MINUTES.toMillis(idleTerminationMinutes)) {
          LOGGER.info("Disconnecting idle computer " + c.getName());
          mesosJenkinsAgent.setPendingDelete(true);

          if (!c.isOffline()) {
            c.setTemporarilyOffline(true, OfflineCause.create(Messages._deletedCause()));
          }
        }
      }

      // Terminate the computer if it is exists for longer than
      // 'maximumTimeToLive'.
      final long timeLivedInMilliseconds =
          DateTimeUtils.currentTimeMillis() - c.getConnectTime();

      if (c.isOnline() && c.isAcceptingTasks()) {

        if (timeLivedInMilliseconds > MINUTES.toMillis(maximumTimeToLive)) {
          LOGGER.info("Disconnecting computer greater maximum TTL " + c.getName());

          if (!c.isOffline()) {
            //set accepting tasks to false, so Computer can reach it's idleTerminationTime
            c.setAcceptingTasks(false);
          }
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.FINE, "Error while check IdleTerminationTime an TTL:", e);
    }
    return 1;
  }

  /**
   * Try to connect to it ASAP to launch the slave agent.
   */
  @Override
  public void start(MesosComputer c) {
    c.connect(false);
  }


  private MesosBuiltOnAction createBuiltOnAction(MesosSlave mesosJenkinsAgent) {
    JenkinsScheduler jenkinsScheduler = (JenkinsScheduler) Mesos.getInstance(mesosJenkinsAgent.getCloud()).getScheduler();

    String mesosAgentHostname = StringUtils.defaultIfBlank(jenkinsScheduler.getResult(mesosJenkinsAgent.getDisplayName()).getSlave().getHostname(), "N/A");

    String jenkinsAgentHostname = "N/A";
    try {
      Computer computer = mesosJenkinsAgent.toComputer();
      if (computer != null) {
        jenkinsAgentHostname = StringUtils.defaultIfBlank(computer.getHostName(), jenkinsAgentHostname);
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error while trying to get hostname of node", e);
    }

    String containerId = StringUtils.defaultIfBlank(mesosJenkinsAgent.getDockerContainerID(), "N/A");

    return new MesosBuiltOnAction(mesosAgentHostname, jenkinsAgentHostname, containerId);
  }

  private Run getCurrentRun(Queue.Executable executable) {
    if (executable instanceof Run) {
      return (Run)executable;
    }

    if (Jenkins.get().getPlugin("workflow-durable-task-step") != null) {
      if (executable.getParent() instanceof ExecutorStepExecution.PlaceholderTask) {
        return ((ExecutorStepExecution.PlaceholderTask) executable.getParent()).run();
      }
    }

    if (executable != null && executable.getParent().getOwnerTask() instanceof Job) {
      Job job = (Job)executable.getParent().getOwnerTask();
      return job.getLastBuild();
    }

    return null;
  }

  private synchronized void addBuiltOnActionOrProperty(Job job) {
    if (job instanceof AbstractProject) {
      try {
        if (job.getProperty(MesosBuiltOnProperty.class) == null) {
          job.addProperty(new MesosBuiltOnProperty());
        }
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Failed to add built on information to " + job.getFullDisplayName(), e);
      }
    } else {
      try {
        job.replaceAction(new MesosBuiltOnProjectAction(job));
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Failed to add MesosBuiltOnProjectAction to " + job.getFullDisplayName(), e);
      }
    }
  }

  @Override
  public void taskAccepted(Executor executor, Queue.Task task) {
    try {
      Run run = getCurrentRun(executor.getCurrentExecutable());
      LOGGER.finest("The current build: " + run);

      addBuiltOnActionOrProperty(run.getParent());

      Node node = executor.getOwner().getNode();
      if (node instanceof MesosSlave) {
        MesosSlave mesosJenkinsAgent = (MesosSlave) node;

        // add to current build
        run.replaceAction(createBuiltOnAction(mesosJenkinsAgent));

        if (mesosJenkinsAgent.getSlaveInfo().isUseSlaveOnce()) {
          // Force Use Once Only on all executors
          mesosJenkinsAgent.getComputer().setAcceptingTasks(false);
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Exception while trying to mark Computer as pending delete:", e);
    }
  }

  @Override
  public void taskCompleted(Executor executor, Queue.Task task, long l) {
    Node node = executor.getOwner().getNode();
    if (node instanceof MesosSlave) {
      try {
        MesosSlave mesosJenkinsAgent = (MesosSlave) node;
        if(mesosJenkinsAgent.getSlaveInfo().isUseSlaveOnce()) {
          // Force Use Once Only on all executors
          mesosJenkinsAgent.setPendingDelete(true);
        }
      } catch (Exception e) {
        LOGGER.log(Level.WARNING,"Exception while trying to mark Computer as pendingDelete:", e);
      }
    }
  }

  @Override
  public void taskCompletedWithProblems(Executor executor, Queue.Task task, long l, Throwable throwable) {
    LOGGER.log(Level.WARNING, "Task on executor '" + executor.getDisplayName() + "' completed with problems:", throwable);
    taskCompleted(executor, task, l);
  }

  /**
   * No registration since this retention strategy is used only for Mesos nodes
   * that we provision automatically.
   */
  public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
    @Override
    public String getDisplayName() {
      return "MESOS";
    }
  }

  boolean isTerminable() {
    return idleTerminationMinutes != 0;
  }
}
