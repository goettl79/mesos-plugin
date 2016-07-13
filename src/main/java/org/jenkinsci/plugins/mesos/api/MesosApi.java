package org.jenkinsci.plugins.mesos.api;

import hudson.Extension;
import hudson.model.AbstractModelObject;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Failure;
import hudson.model.Node;
import hudson.model.UnprotectedRootAction;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.mesos.JenkinsScheduler;
import org.jenkinsci.plugins.mesos.Mesos;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosSlave;
import org.jenkinsci.plugins.mesos.Messages;
import org.jenkinsci.plugins.mesos.config.acl.ACLEntry;
import org.jenkinsci.plugins.mesos.config.acl.MesosFrameworkToItemMapper;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveDefinitions;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.SlaveDefinitionsConfiguration;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
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
   * Creates a new Jenkins Mesos slave by providing a task id in the URL, which corresponds to an existing task request
   * made by Mesos. Basically, the task id is the resulting name of the Jenkins slave.
   *
   * <br /><br />
   *
   * Example: &lt;JenkinsURL&gt;/mesos/createSlave/&lt;taskId&gt;
   *
   * @param req Request which contains the task id in the path
   * @param rsp Response object which will contain the status code and message
   * @return a message containing whether or not creating and adding the slave was successful
   */
  @SuppressWarnings("unused")
  public synchronized String doCreateSlave(StaplerRequest req, StaplerResponse rsp) throws IOException {
    String taskId = req.getRestOfPath().replaceFirst("/", "");
    return doCreateSlaveWithParameter(taskId, rsp);
  }

  /**
   * Creates a new Jenkins Mesos slave by providing a task id as a parameter (taskId) which corresponds to an existing
   * task request made by Mesos (Jenkins slave name).
   *
   * <br /><br />
   *
   * Example: &lt;JenkinsURL&gt;/mesos/createSlave?taskId=&lt;taskId&gt;
   *
   * @param taskId The id of the task (which is equal to the Jenkins slave name) to create
   * @param rsp Response object which will contain the status code and message
   * @return a message containing whether or not creating and adding the slave was successful
   */
  @RequirePOST
  @SuppressWarnings("unused")
  public synchronized String doCreateSlaveWithParameter(
      @QueryParameter(fixEmpty = true, required = true) String taskId,
      StaplerResponse rsp) throws IOException {
    //TODO: make Mesos authenticate itself on Jenkins to enforce ADMINISTER permission
    //Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

    if(StringUtils.isBlank(taskId)) {
      rsp.setStatus(SC_BAD_REQUEST);
      return "Please specify a valid taskId";
    }

    final Jenkins jenkins = Jenkins.getInstance();
    // check for existing connections
    Node n = jenkins.getNode(taskId);
    if (n != null) {
      Computer c = n.toComputer();
      if (c != null && c.isOnline()) {
        // this is an existing connection, we'll only cause issues if we trample over an online connection
        rsp.setStatus(SC_BAD_REQUEST);
        return String.format("A slave called '%s' is already created and online", taskId);
      }
    }

    try {
      String message = createSlave(taskId);
      rsp.setStatus(SC_OK);
      return message;
    } catch (Failure e) {
      rsp.setStatus(SC_BAD_REQUEST);
      LOGGER.log(Level.FINER, "Could not create slave with name '" + taskId + "'", e);
      return e.getMessage();
    } catch (Exception e) {
      rsp.setStatus(SC_INTERNAL_SERVER_ERROR);
      LOGGER.log(Level.SEVERE, "Could not create slave with name '" + taskId + "'", e);
      return e.getMessage();
    }
  }

  private String createSlave(String taskId) throws FormException, IOException {
    Jenkins jenkins = Jenkins.getInstance();
    Collection<MesosCloud> mesosClouds = Mesos.getAllMesosClouds();

    for (MesosCloud mesosCloud : mesosClouds) {
      JenkinsScheduler jenkinsScheduler = (JenkinsScheduler) Mesos.getInstance(mesosCloud).getScheduler();
      JenkinsScheduler.Result result = jenkinsScheduler.getResult(taskId);

      if (result != null) {
        Mesos.JenkinsSlave jenkinsSlave = result.getSlave();
        int executors = jenkinsSlave.getNumExecutors();
        MesosSlaveInfo mesosSlaveInfo =
            mesosCloud.getSlaveInfo(mesosCloud.getSlaveInfos(), jenkins.getLabel(jenkinsSlave.getLabel()));

        LOGGER.info("Add new Jenkins slave with name '" + taskId + "' from HTTP request");
        MesosSlave slave = new MesosSlave(mesosCloud, taskId, executors, mesosSlaveInfo, jenkinsSlave.getLinkedItem());
        jenkins.addNode(slave);

        return String.format("Added slave '%s' to Jenkins", taskId);
      }
    }

    throw new Failure(String.format("Corresponding request for taskId '%s' does not exist", taskId));
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
      @QueryParameter(fixEmpty = true, required = true) String frameworkName,
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

  private boolean isXmlContentType(String requestContentType) {
    if (StringUtils.isBlank(requestContentType)) {
      throw new Failure(Messages.MesosApi_NoContentTypeHeader());
    }

    return requestContentType.startsWith("application/xml") || requestContentType.startsWith("text/xml");
  }

  private boolean isValidSlaveDefinitionsRequest(StaplerRequest req) {
    return isXmlContentType(req.getContentType()) && req.getContentLength() > 0;
  }

  /**
   * Adds an entry with Mesos Slave definitions/infos to the configuration.
   *
   * @param definitionsName The name of the definitions entry to add
   * @param req Request object which contains the XML of the configuration
   * @param rsp Response object which will contain the status code and message
   * @return a message containing whether or not adding the specified definitions was successful
   */
  @RequirePOST
  @SuppressWarnings("unused")
  public synchronized String doAddSlaveDefinitionsEntry(
      @QueryParameter(fixEmpty = true, required = true) String definitionsName,
      StaplerRequest req,
      StaplerResponse rsp) throws IOException {
    Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

    final SlaveDefinitionsConfiguration.DescriptorImpl descriptor =
        (SlaveDefinitionsConfiguration.DescriptorImpl)Jenkins.getInstance().getDescriptorOrDie(SlaveDefinitionsConfiguration.class);

    if (isValidSlaveDefinitionsRequest(req)) {
      try {
        MesosSlaveDefinitions slaveDefinitions = descriptor.addSlaveDefinitionsEntry(definitionsName, req.getInputStream());

        rsp.setStatus(StaplerResponse.SC_OK);
        return Messages.MesosApi_SuccessfullyAddedSlaveDefinitions(slaveDefinitions.toString());
      } catch (Failure e) {
        rsp.setStatus(StaplerResponse.SC_BAD_REQUEST);
        return e.getMessage();
      }
    }

    rsp.setStatus(StaplerResponse.SC_BAD_REQUEST);
    return Messages.MesosApi_CreateSlaveDefinitionsEntryBadRequest();
  }

  /**
   * Updates an existing entry with Mesos Slave definitions/infos of the configuration.
   *
   * @param definitionsName The name of the definitions entry to update
   * @param req Request object which contains the XML of the configuration
   * @param rsp Response object which will contain the status code and message
   * @return a message containing whether or not adding the specified definitions was successful
   */
  @RequirePOST
  @SuppressWarnings("unused")
  public synchronized String doUpdateSlaveDefinitionsEntry(
      @QueryParameter(fixEmpty = true, required = true) String definitionsName,
      StaplerRequest req,
      StaplerResponse rsp) throws IOException {
    Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

    final SlaveDefinitionsConfiguration.DescriptorImpl descriptor =
        (SlaveDefinitionsConfiguration.DescriptorImpl)Jenkins.getInstance().getDescriptorOrDie(SlaveDefinitionsConfiguration.class);

    if (isValidSlaveDefinitionsRequest(req)) {
      try {
        MesosSlaveDefinitions oldSlaveDefinitions = descriptor.updateSlaveDefinitionsEntry(definitionsName, req.getInputStream());

        rsp.setStatus(StaplerResponse.SC_OK);
        return Messages.MesosApi_SuccessfullyUpdatedSlaveDefinitions(oldSlaveDefinitions.toString());
      } catch (Failure e) {
        rsp.setStatus(StaplerResponse.SC_BAD_REQUEST);
        return e.getMessage();
      }
    }

    rsp.setStatus(StaplerResponse.SC_BAD_REQUEST);
    return Messages.MesosApi_CreateSlaveDefinitionsEntryBadRequest();
  }

  /**
   * Removes an entry with Mesos Slave definitions/infos from the configuration.
   *
   * @param definitionsName The name of the definitions entry to remove
   * @param rsp Response object which will contain the status code and message
   * @return a message containing whether or not removing the specified definitions entry was successful
   */
  @RequirePOST
  @SuppressWarnings("unused")
  public synchronized String doRemoveSlaveDefinitionsEntry(
      @QueryParameter(fixEmpty = true, required = true) String definitionsName,
      StaplerResponse rsp) {
    Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

    final SlaveDefinitionsConfiguration.DescriptorImpl descriptor =
        (SlaveDefinitionsConfiguration.DescriptorImpl)Jenkins.getInstance().getDescriptorOrDie(SlaveDefinitionsConfiguration.class);

    try {
      MesosSlaveDefinitions removedSlaveDefinitions = descriptor.removeSlaveDefinitionsEntry(definitionsName);

      rsp.setStatus(StaplerResponse.SC_OK);
      if (removedSlaveDefinitions != null) {
        return Messages.MesosApi_SuccessfullyRemovedSlaveDefinitionsEntry(removedSlaveDefinitions.toString());
      } else {
        return Messages.MesosApi_NotRemovedSlaveDefinitionsEntry(definitionsName);
      }
    } catch (Failure e) {
      rsp.setStatus(StaplerResponse.SC_BAD_REQUEST);
      return e.getMessage();
    }
  }

}
