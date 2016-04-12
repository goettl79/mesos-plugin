package org.jenkinsci.plugins.mesos.api;

import hudson.Extension;
import hudson.model.*;
import hudson.model.Descriptor.FormException;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.*;
import org.jenkinsci.plugins.mesos.Messages;
import org.jenkinsci.plugins.mesos.config.acl.ACLEntry;
import org.jenkinsci.plugins.mesos.config.acl.MesosFrameworkToItemMapper;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.Date;
import java.util.logging.Logger;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

/**
 * Exposes an entry point to add a new mesos slave.
 */
@Extension
public class MesosApi extends AbstractModelObject implements UnprotectedRootAction {

  private static final Logger LOGGER = Logger.getLogger(MesosApi.class.getName());

  public String getDisplayName() {
    return "Mesos Plugin Api";
  }

  public String getSearchUrl() {
    return getUrlName();
  }

  public String getIconFileName() {
    // TODO
    return null;
  }

  public String getUrlName() {
    return "mesos";
  }

  /**
   * Adds a new mesos slave.
   * taskID == SlaveName
   */
  @SuppressWarnings("unused")
  public void doCreateSlave(StaplerRequest req, StaplerResponse rsp) throws IOException {
    try {
      String taskID = req.getRestOfPath().replaceFirst("/", "");

      if(taskID == null || taskID.isEmpty()) {
        rsp.setStatus(SC_CONFLICT);
        rsp.setContentType("text/plain; UTF-8");
        rsp.getWriter().printf("No such Mesos Task %s", taskID);
        return;
      }

      final Jenkins jenkins = Jenkins.getInstance();
      // check for existing connections
      {
        Node n = jenkins.getNode(taskID);
        if (n != null) {
          Computer c = n.toComputer();
          if (c != null && c.isOnline()) {
            // this is an existing connection, we'll only cause issues if we trample over an online connection

            rsp.setStatus(SC_CONFLICT);
            rsp.setContentType("text/plain; UTF-8");
            rsp.getWriter().printf("A slave called '%s' is already created and on-line%n", taskID);
            return;
          }
        }
      }

      for (Cloud c : jenkins.clouds) {
        if (c instanceof MesosCloud) {
          MesosCloud mesosCloud = (MesosCloud) c;
          JenkinsScheduler jenkinsScheduler = (JenkinsScheduler) Mesos.getInstance(mesosCloud).getScheduler();
          JenkinsScheduler.Result result = jenkinsScheduler.getResult(taskID);

          if (result != null) {
            int executors = result.getSlave().getNumExecutors();
            MesosSlaveInfo mesosSlaveInfo = mesosCloud.getSlaveInfo(mesosCloud.getSlaveInfos(), jenkins.getLabel(result.getSlave().getLabel()));

            LOGGER.info("add new Slave from WebRequest " + taskID);
            MesosSlave slave = new MesosSlave(mesosCloud, taskID, executors, mesosSlaveInfo, result.getSlave().getLinkedItem());

            jenkins.addNode(slave);


            rsp.setStatus(SC_OK);
            rsp.setContentType("text/plain; UTF-8");
            rsp.getWriter().printf("[%s] Added node %s to jenkins", (new Date()).toLocaleString() ,  taskID);
            return;
          }
        }
      }

      rsp.setStatus(SC_CONFLICT);
      rsp.setContentType("text/plain; UTF-8");
      rsp.getWriter().printf("Couldn't find any request with ID %s", taskID);
      return;

    } catch (FormException e) {
      LOGGER.fine("Error while handling MesosAPI WebRequest " + e.getMessage());
      e.printStackTrace();
    }
  }


  /**
   * Adds an ACL entry with the specified item pattern and name of the framework to the Mesos Framework to Jenkins
   * Item pattern ACL entries.
   *
   * @param itemPattern Pattern (regular expression) of the item to match
   * @param frameworkName Name of the (already configured) framework where the item should provision a task
   * @param rsp Response object which will contain the status code and message
   * @return a message containing whether or not adding the ACL entry was successful
   */
  @RequirePOST
  @SuppressWarnings("unused")
  public synchronized String doAddACLEntry(
      @QueryParameter(fixEmpty = true, required = true) String itemPattern,
      @QueryParameter(fixEmpty = true, required = true) String frameworkName,
      StaplerResponse rsp) {
    Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

    final MesosFrameworkToItemMapper.DescriptorImpl descriptor =
        (MesosFrameworkToItemMapper.DescriptorImpl)Jenkins.getInstance().getDescriptorOrDie(MesosFrameworkToItemMapper.class);

    try {
      ACLEntry newACLEntry = descriptor.addACLEntry(itemPattern, frameworkName);

      rsp.setStatus(StaplerResponse.SC_OK);
      return Messages.MesosApi_SuccessfullyAddedACLEntry(newACLEntry.toString());
    } catch (Failure e) {
      rsp.setStatus(StaplerResponse.SC_BAD_REQUEST);
      return e.getMessage();
    }
  }

  /**
   * Removes an ACL entry with an equal item pattern from the Mesos Framework to Jenkins Item pattern ACL entries.
   *
   * @param itemPattern The exact item pattern of the specific ACL entry to delete
   * @param rsp Response object which will contain the status code and message
   * @return a message containing whether or not the removal of the ACL entry was successful
   */
  @RequirePOST
  @SuppressWarnings("unused")
  public synchronized String doRemoveACLEntry(
      @QueryParameter(fixEmpty = true, required = true) String itemPattern,
      StaplerResponse rsp) {
    Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

    final MesosFrameworkToItemMapper.DescriptorImpl descriptor =
        (MesosFrameworkToItemMapper.DescriptorImpl)Jenkins.getInstance().getDescriptorOrDie(MesosFrameworkToItemMapper.class);

    try {
      ACLEntry removedACLEntry = descriptor.removeACLEntry(itemPattern);

      rsp.setStatus(StaplerResponse.SC_OK);
      if (removedACLEntry != null) {
        return Messages.MesosApi_SuccessfullyRemovedACLEntry(removedACLEntry.toString());
      } else {
        return Messages.MesosApi_NotRemovedACLEntry(itemPattern);
      }
    } catch (Failure e) {
      rsp.setStatus(StaplerResponse.SC_BAD_REQUEST);
      return e.getMessage();
    }
  }

  /**
   * Changes the default framework name to the new specified framework.
   *
   * @param frameworkName Name of the framework which should be used as new default framework name
   * @param rsp Response object which will contain the status code and message
   * @return a message containing whether or not changing the default framework name was successful
   */
  @RequirePOST
  @SuppressWarnings("unused")
  public synchronized String doChangeDefaultFrameworkName(
      @QueryParameter(fixEmpty = true) String frameworkName,
      StaplerResponse rsp) {
    Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

    final MesosFrameworkToItemMapper.DescriptorImpl descriptor =
        (MesosFrameworkToItemMapper.DescriptorImpl)Jenkins.getInstance().getDescriptorOrDie(MesosFrameworkToItemMapper.class);

    try {
      String oldFrameworkName = descriptor.changeDefaultFrameworkName(frameworkName);

      rsp.setStatus(StaplerResponse.SC_OK);
      return Messages.MesosApi_SuccessfullyChangedDefaultFrameworkName(oldFrameworkName, frameworkName);
    } catch (Failure e) {
      rsp.setStatus(StaplerResponse.SC_BAD_REQUEST);
      return e.getMessage();
    }
  }

}
