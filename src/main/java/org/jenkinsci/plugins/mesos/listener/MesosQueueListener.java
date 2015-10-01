package org.jenkinsci.plugins.mesos.listener;

import hudson.Extension;
import hudson.model.*;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosSingleUseSlave;
import org.jenkinsci.plugins.mesos.MesosSlaveInfo;
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
    boolean containsMesosSingleUseSlaveClass = false;
    try {
      Project proj = (Project) bi.task;
      for (Object o : proj.getBuildWrappers().values()) {
        if (o.getClass().equals(MesosSingleUseSlave.class)) {
          containsMesosSingleUseSlaveClass = true;
          break;
        }
      }
    } catch (Exception e) {
      LOGGER.warning("Error while evaluating BuildWrappers, force provisioning will be ignored" + e.getMessage());
    }

    if(containsMesosSingleUseSlaveClass) {
      forceProvisionInNewThreadIfPossible(bi.getAssignedLabel(), bi);
    }
  }

  public void forceProvisionInNewThreadIfPossible(final Label label, final Queue.BuildableItem bi) {
    if(label != null) {
      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          forceProvisionIfPossible(label, bi);
        }
      }, "ForceNewMesosNode for " + label.getName());
      t.start();
    }
  }

  public void forceProvisionIfPossible(final Label label, Queue.BuildableItem bi) {
    if(label != null) {
      Node future = null;
      CLOUD:
      for (Cloud c : Jenkins.getInstance().clouds) {
        if (c.canProvision(label)) {
          if (c instanceof MesosCloud) {
            MesosCloud mesosCloud = (MesosCloud) c;
            MesosSlaveInfo mesosSlaveInfo = mesosCloud.getSlaveInfo(mesosCloud.getSlaveInfos(), label);

            if (mesosSlaveInfo.isForceProvisioning()) {
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
}
