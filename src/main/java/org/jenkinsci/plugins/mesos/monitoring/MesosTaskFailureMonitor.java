package org.jenkinsci.plugins.mesos.monitoring;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.util.HttpResponses;
import jenkins.management.AsynchronousAdministrativeMonitor;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.Mesos;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Extension
public class MesosTaskFailureMonitor extends AsynchronousAdministrativeMonitor {

  private Set<Mesos.JenkinsSlave> failedSlaves = Collections.synchronizedSet(new LinkedHashSet<Mesos.JenkinsSlave>());

  @Override
  public String getDisplayName() {
    return "Mesos Task Failure Monitor";
  }

  public static MesosTaskFailureMonitor getInstance() {
    return Jenkins.getInstance().getExtensionList(AsynchronousAdministrativeMonitor.class).get(MesosTaskFailureMonitor.class);
  }

  @Override
  public boolean isActivated() {
    return !failedSlaves.isEmpty() || getLogFile().exists();
  }

  @Override
  public void fix(TaskListener taskListener) throws Exception {
    PrintStream logger = taskListener.getLogger();
    Jenkins jenkins = Jenkins.getInstance();
    Set<Mesos.JenkinsSlave> failedSlaves = getFailedSlaves();
    for (Mesos.JenkinsSlave jenkinsSlave : failedSlaves) {
      Node node = Jenkins.getInstance().getNode(jenkinsSlave.getName());
      if (node != null) {
        try {
          jenkins.removeNode(node);
          logger.println("Removed node '" + jenkinsSlave + "' from Jenkins");
        } catch (IOException e) {
          logger.println("Could not remove '" + jenkinsSlave + "' because: " + e.getMessage());
        }
      }

      Label label = jenkins.getLabel(jenkinsSlave.getLabel());
      if (label != null) {
        //rerequest new Task
        for (Cloud c : Jenkins.getInstance().clouds) {
          if (c.canProvision(label)) {
            if (c instanceof MesosCloud) {
              MesosCloud mesosCloud = (MesosCloud) c;
              if (mesosCloud.isItemForMyFramework(jenkinsSlave.getLinkedItem())) {
                MesosSlaveInfo mesosSlaveInfo = mesosCloud.getSlaveInfo(mesosCloud.getSlaveInfos(), label);

                logger.println("Request new task for " + label.getDisplayName());
                mesosCloud.requestNodes(label, jenkinsSlave.getNumExecutors(), jenkinsSlave.getLinkedItem());
              }
            }
          }
        }
      }
    }

    this.failedSlaves.removeAll(failedSlaves);
  }

  @RequirePOST
  public HttpResponse doFix() {
    start(false);
    return HttpResponses.forwardToPreviousPage();
  }

  @RequirePOST
  public HttpResponse doDismiss() {
    getLogFile().delete();
    failedSlaves = Collections.synchronizedSet(new LinkedHashSet<Mesos.JenkinsSlave>());
    return HttpResponses.forwardToPreviousPage();
  }

  public boolean addFailedSlave(Mesos.JenkinsSlave slave) {
    return failedSlaves.add(slave);
  }

  public Set<Mesos.JenkinsSlave> getFailedSlaves() {
    return Collections.unmodifiableSet(failedSlaves);
  }
}
