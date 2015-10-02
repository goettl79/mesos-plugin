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
    forceProvisionInNewThreadIfPossible(bi.getAssignedLabel());
  }

  public void forceProvisionInNewThreadIfPossible(final Label label) {
    if(label != null) {
      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          forceProvisionIfPossible(label);
        }
      }, "ForceNewMesosNode for " + label.getName());
      t.start();
    }
  }

  public void forceProvisionIfPossible(final Label label) {
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
