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
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Extension
public class MesosTaskFailureMonitor extends AsynchronousAdministrativeMonitor {

  private Map<Mesos.JenkinsSlave, Mesos.SlaveResult.FAILED_CAUSE> failedSlaves;

  public MesosTaskFailureMonitor() {
    init();
  }

  private void init() {
    this.failedSlaves = new ConcurrentHashMap<Mesos.JenkinsSlave, Mesos.SlaveResult.FAILED_CAUSE>();
  }

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
    Map<Mesos.JenkinsSlave, Mesos.SlaveResult.FAILED_CAUSE> failedSlavesCopy = new HashMap<Mesos.JenkinsSlave, Mesos.SlaveResult.FAILED_CAUSE>(failedSlaves);

    for (Mesos.JenkinsSlave failedSlave : failedSlavesCopy.keySet()) {
      try {
        this.failedSlaves.remove(failedSlave);
        removeExistingNode(failedSlave, logger);
        requestNewNode(failedSlave, logger);
      } catch (Exception e) {
        logger.println("Unable to fix failed slave '" + failedSlave + "', because:");
        e.printStackTrace(logger);
      }
    }
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
  public HttpResponse doFix(StaplerRequest req) {
    if(req.hasParameter("fix")) {
      fixTasks();
    } else if(req.hasParameter("dismiss")) {
      ignoreTasks();
    }

    return HttpResponses.forwardToPreviousPage();
  }

  public void fixTasks() {
    start(false);
  }

  public void ignoreTasks() {
    getLogFile().delete();
    init();
  }

  public void addFailedSlave(Mesos.JenkinsSlave slave, Mesos.SlaveResult.FAILED_CAUSE cause) {
    failedSlaves.put(slave, cause);
  }

  public Map<Mesos.JenkinsSlave, Mesos.SlaveResult.FAILED_CAUSE> getFailedSlaves() {
    return Collections.unmodifiableMap(failedSlaves);
  }
}
