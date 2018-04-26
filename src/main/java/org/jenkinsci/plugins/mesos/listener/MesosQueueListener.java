package org.jenkinsci.plugins.mesos.listener;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import jenkins.model.Jenkins;
import org.apache.mesos.Scheduler;
import org.jenkinsci.plugins.mesos.JenkinsScheduler;
import org.jenkinsci.plugins.mesos.Mesos;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;

import java.util.logging.Level;
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
    forceProvisionInNewThreadIfPossible(bi.getAssignedLabel(), bi);
  }

  public void forceProvisionInNewThreadIfPossible(final Label label, final Queue.BuildableItem bi) {
    if (label != null) {
      Thread t = new Thread(() -> forceProvisionIfPossible(label, bi), "ForceNewMesosNode for " + label.getName());
      t.start();
    }
  }

  /***
   * Collect cancelled Items from queue
   * due to the fact, that for every buildable item (Labeltype Mesos) was sent a mesos request, this request must be
   * cancelled.
   * @param li item which left the queue
   */
  @Override
  public void onLeft(Queue.LeftItem li) {
    try {
      if (li.isCancelled() && li.getAssignedLabel() != null) {
        Jenkins jenkins = Jenkins.get();
        for (Cloud cloud : jenkins.clouds) {
          if (cloud instanceof MesosCloud) {
            MesosCloud mesosCloud = (MesosCloud) cloud;
            Mesos mesos = Mesos.getInstance(mesosCloud);
            if (mesos != null) {
              Scheduler scheduler = mesos.getScheduler();
              if (scheduler != null) {
                if (scheduler instanceof JenkinsScheduler) {
                  JenkinsScheduler jenkinsScheduler = (JenkinsScheduler) scheduler;
                  if (jenkinsScheduler.removeRequestForLinkedItem(mesosCloud.getFullNameOfTask(li.task))) {
                    return; //it should only remove one task
                  }
                }
              }
            }
          }
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error while removing request from buildable Item", e);
    }
  }

  public void forceProvisionIfPossible(final Label label, Queue.BuildableItem bi) {
    // TODO: get this from actual configuration

    if (label != null) {
      Node future = null;
      CLOUD:
      for (Cloud c : Jenkins.get().clouds) {
        if (c.canProvision(label)) {
          if (c instanceof MesosCloud) {
            MesosCloud mesosCloud = (MesosCloud) c;
            if (mesosCloud.isItemForMyFramework(bi)) {
              MesosSlaveInfo mesosSlaveInfo = mesosCloud.getSlaveInfo(mesosCloud.getSlaveInfos(), label);

              if(mesosSlaveInfo.isUseSlaveOnce()) {
                int numExecutors = 1;
                for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                  if (cl.canProvision(mesosCloud, label, numExecutors) != null) {
                    break CLOUD;
                  }
                }
                mesosCloud.requestNodes(label, numExecutors, mesosCloud.getFullNameOfItem(bi));
              }
            }
          }
        }
      }
    }
  }
}
