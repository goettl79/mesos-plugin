package org.jenkinsci.plugins.mesos.api;

import hudson.Extension;
import hudson.model.*;
import hudson.model.Descriptor.FormException;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.*;
import org.kohsuke.stapler.*;

import javax.servlet.ServletOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;
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
            MesosSlave slave = new MesosSlave(mesosCloud, taskID, executors, mesosSlaveInfo);

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
}
