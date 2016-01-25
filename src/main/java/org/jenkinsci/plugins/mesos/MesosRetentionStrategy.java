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

import static hudson.util.TimeUnit2.MINUTES;

import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import hudson.model.*;
import hudson.slaves.SlaveComputer;
import org.joda.time.DateTimeUtils;
import hudson.slaves.OfflineCause;

import hudson.slaves.RetentionStrategy;

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
  private ReentrantLock checkLock = new ReentrantLock(false);

  private static final Logger LOGGER = Logger
      .getLogger(MesosRetentionStrategy.class.getName());

  public MesosRetentionStrategy(int idleTerminationMinutes, int maximumTimeToLive) {
    this.idleTerminationMinutes = idleTerminationMinutes;
    this.maximumTimeToLive = maximumTimeToLive;
  }


  @Override
  public long check(MesosComputer c) {
    if (!checkLock.tryLock()) {
      return 1;
    } else {
      try {
        return checkInternal(c);
      } finally {
        checkLock.unlock();
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
    if (c.getNode() == null) {
      return 1;
    }

    //if an executor is "dead", determining the conntectionTime may cause an NullPointer Exception
    try {
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
          c.getNode().setPendingDelete(true);

          if (!c.isOffline()) {
            c.setTemporarilyOffline(true, OfflineCause.create(Messages._DeletedCause()));
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
      LOGGER.fine("Error while check IdleTerminationTime an TTL: "+e.getMessage());
      e.printStackTrace();
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

  @Override
  public void taskAccepted(Executor executor, Queue.Task task) {
    try {
      Node node = executor.getOwner().getNode();
      if (node != null && node instanceof MesosSlave) {
        MesosSlave mesosSlave = (MesosSlave) executor.getOwner().getNode();
        if (mesosSlave.getSlaveInfo().isUseSlaveOnce()) {
          // Force Use Once Only on all executors
          ((SlaveComputer) mesosSlave.getComputer()).setAcceptingTasks(false);
        }
      }
    } catch (Exception e) {
      LOGGER.warning("Exception while trying to mark Computer as pending delete: " + e);
      e.printStackTrace();
    }
  }

  @Override
  public void taskCompleted(Executor executor, Queue.Task task, long l) {
    Node node = executor.getOwner().getNode();
    if (node != null && node instanceof MesosSlave) {
      try {
        MesosSlave mesosSlave = (MesosSlave) node;
        if(mesosSlave.getSlaveInfo().isUseSlaveOnce()) {
          // Force Use Once Only on all executors
          mesosSlave.setPendingDelete(true);
        }
      } catch (Exception e) {
        LOGGER.warning("Exception while trying to mark Computer as pendingDelete: " + e);
        e.printStackTrace();
      }
    }
  }

  @Override
  public void taskCompletedWithProblems(Executor executor, Queue.Task task, long l, Throwable throwable) {
    LOGGER.warning("Task completed with Problems " + throwable.getMessage() + " on " + executor.getDisplayName());
    throwable.printStackTrace();
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
