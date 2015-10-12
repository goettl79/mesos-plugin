package org.jenkinsci.plugins.mesos.listener;

import hudson.Extension;
import hudson.model.*;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import jenkins.model.Jenkins;
import org.apache.mesos.Scheduler;
import org.jenkinsci.plugins.mesos.*;

import java.util.logging.Logger;

@SuppressWarnings("rawtypes")
@Extension
public class MesosQueueListener extends QueueListener {

  private static final Logger LOGGER = Logger.getLogger(MesosQueueListener.class.getName());

  @SuppressWarnings("unchecked")
  public MesosQueueListener() {
    super();
  }

  @Override
  public void onEnterBuildable(Queue.BuildableItem bi) {
    forceProvisionInNewThreadIfPossible(bi.getAssignedLabel());
  }

  public void forceProvisionInNewThreadIfPossible(final Label label) {
    if (label != null) {
      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          forceProvisionIfPossible(label);
        }
      }, "ForceNewMesosNode for " + label.getName());
      t.start();
    }
  }

  /***
   * Collect cancelled Items from queue
   * due to the fact, that for every buildable item (Labeltype Mesos) was sent a mesos request, this request must be
   * cancelled.
   * @param li
   */
  @Override
  public void onLeft(Queue.LeftItem li) {
    try {
      if (li.isCancelled() && li.getAssignedLabel() != null) {
        Jenkins jenkins = Jenkins.getInstance();
        for (Cloud cloud : jenkins.clouds) {
          if (cloud instanceof MesosCloud) {
            MesosCloud mesosCloud = (MesosCloud) cloud;
            Mesos mesos = Mesos.getInstance(mesosCloud);
            if (mesos != null) {
              Scheduler scheduler = mesos.getScheduler();
              if (scheduler != null) {
                if (scheduler instanceof JenkinsScheduler) {
                  JenkinsScheduler jenkinsScheduler = (JenkinsScheduler) scheduler;
                  if (jenkinsScheduler.removeRequestMatchingLabel(li.getAssignedLabel().getDisplayName())) {
                    return; //it should only remove one task
                  }
                }
              }
            }
          }
        }
      }
    } catch (Exception e) {
      LOGGER.fine("Error while removing request from buildable Item " + e.getMessage());
      e.printStackTrace();
    }
  }

  public void forceProvisionIfPossible(final Label label) {
    if (label != null) {
      Node future = null;
      CLOUD:
      for (Cloud c : Jenkins.getInstance().clouds) {
        if (c.canProvision(label)) {
          if (c instanceof MesosCloud) {
            MesosCloud mesosCloud = (MesosCloud) c;
            MesosSlaveInfo mesosSlaveInfo = mesosCloud.getSlaveInfo(mesosCloud.getSlaveInfos(), label);

            int numExecutors = 1;
            for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
              if (cl.canProvision(mesosCloud, label, numExecutors) != null) {
                break CLOUD;
              }
            }
            mesosCloud.forceNewMesosNodes(label, numExecutors);
          }
        }
      }
    }
  }
}
