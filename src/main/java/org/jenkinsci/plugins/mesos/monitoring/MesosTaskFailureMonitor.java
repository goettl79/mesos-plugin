package org.jenkinsci.plugins.mesos.monitoring;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.HttpResponses;
import jenkins.management.AsynchronousAdministrativeMonitor;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.Mesos;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosComputer;
import org.jenkinsci.plugins.mesos.MesosSlave;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.PrintStream;
import java.util.Collection;
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
    Set<Mesos.JenkinsSlave> failedSlaves = getFailedSlaves();
    for (Mesos.JenkinsSlave failedSlave : failedSlaves) {
      removeExistingNode(failedSlave, logger);
      requestNewNode(failedSlave, logger);
    }

    this.failedSlaves.removeAll(failedSlaves);
  }

  private void requestNewNode(Mesos.JenkinsSlave failedSlave, PrintStream logger) {
    Jenkins jenkins = Jenkins.getInstance();

    try {
      Label label = jenkins.getLabel(failedSlave.getLabel());
      if (label != null) {
        //rerequest new Task
        Collection<MesosCloud> mesosClouds = Mesos.getAllMesosClouds();
        for (MesosCloud mesosCloud : mesosClouds) {
          if (mesosCloud.canProvision(label)) {
              if (mesosCloud.isItemForMyFramework(failedSlave.getLinkedItem())) {
                logger.println("Request new task for " + label.getDisplayName());
                mesosCloud.requestNodes(label, failedSlave.getNumExecutors(), failedSlave.getLinkedItem());
              }
            }
        }
      }
    } catch (Exception e) {
      logger.println("Could not request node for '" + failedSlave
          + "' (label: '" + failedSlave.getLabel() + "', linkedItem: '" + failedSlave.getLinkedItem() + "'), because:");
      e.printStackTrace(logger);
    }
  }

  private void removeExistingNode(Mesos.JenkinsSlave failedSlave, PrintStream logger) {
    Jenkins jenkins = Jenkins.getInstance();
    Node node = jenkins.getNode(failedSlave.getName());
    if (node != null) {
      if(node instanceof MesosSlave) {
        MesosSlave mesosSlave = (MesosSlave) node;
        MesosComputer mesosComputer = (MesosComputer) mesosSlave.toComputer();
        mesosComputer.deleteSlave();
      }
      logger.println("Removed node '" + failedSlave + "' from Jenkins");
    }
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
