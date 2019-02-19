/*
 * Copyright 2013 Twitter, Inc. and other contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.mesos;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Queue;
import hudson.model.*;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.Cloud;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.mesos.MesosNativeLibrary;
import org.jenkinsci.plugins.mesos.actions.MesosBuiltOnAction;
import org.jenkinsci.plugins.mesos.actions.MesosBuiltOnProjectAction;
import org.jenkinsci.plugins.mesos.config.acl.MesosFrameworkToItemMapper;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveDefinitions;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.SlaveDefinitionsConfiguration;
import org.jenkinsci.plugins.mesos.scheduling.JenkinsSlave;
import org.jenkinsci.plugins.mesos.scheduling.SlaveRequest;
import org.jenkinsci.plugins.mesos.scheduling.SlaveResult;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MesosCloud extends Cloud {
  private String nativeLibraryPath;
  private String master;
  private String description;
  private String frameworkName;
  private String role;
  private double maxCpus;
  private int maxMem;
  private String slavesUser;
  private String credentialsId;
  /**
   * @deprecated Create credentials then use credentialsId instead.
   */
  @Deprecated
  private transient String principal;
  /**
   * @deprecated Create credentials then use credentialsId instead.
   */
  @Deprecated
  private transient String secret;  
  private final boolean checkpoint; // Set true to enable checkpointing. False by default.
  private boolean onDemandRegistration; // If set true, this framework disconnects when there are no builds in the queue and re-registers when there are.
  private String jenkinsURL;
  private String grafanaDashboardURL;

  // Find the default values for these variables in
  // src/main/resources/org/jenkinsci/plugins/mesos/MesosCloud/config.jelly.
  private String slaveDefinitionsName;

  private String defaultSlaveLabel;

  private String schedulerName;

  private static final Logger LOGGER = Logger.getLogger(MesosCloud.class.getName());

  // We allocate 10% more memory to the Mesos task to account for the JVM overhead.
  private static final double JVM_MEM_OVERHEAD_FACTOR = 0.1;

  // TODO: this is bad man
  @SuppressFBWarnings
  private static volatile boolean nativeLibraryLoaded = false;

  public static final String DEFAULT_SLAVE_LABEL_NONE = "None";

  /**
   * We want to start the Mesos scheduler as part of the initialization of Jenkins
   * and after the cloud class values have been restored from persistence.If this is
   * the very first time, this method will be NOOP as MesosCloud is not registered yet.
   */

  @Initializer(after=InitMilestone.JOB_LOADED)
  public static void init() {
    Jenkins jenkins = Jenkins.get();
    List<Node> slaves = jenkins.getNodes();

    // Turning the AUTOMATIC_SLAVE_LAUNCH flag off because the below slave removals
    // causes computer launch in other slaves that have not been removed yet.
    // To study how a slave removal updates the entire list, one can refer to
    // Hudson NodeProvisioner class and follow this method chain removeNode() ->
    // setNodes() -> updateComputerList() -> updateComputer().
    Jenkins.AUTOMATIC_SLAVE_LAUNCH = false;
    for (Node n : slaves) {
      //Remove all slaves that were persisted when Jenkins shutdown.
      if (n instanceof MesosSlave) {
        ((MesosSlave)n).terminate();
      }
    }

    // Turn it back on for future real slaves.
    Jenkins.AUTOMATIC_SLAVE_LAUNCH = true;

    for (Cloud c : jenkins.clouds) {
      if( c instanceof MesosCloud) {
        // Register mesos framework on init, if on demand registration is not enabled.
        if (!((MesosCloud) c).isOnDemandRegistration()) {
          ((MesosCloud)c).restartMesos();
        }
      }
    }
  }

  @DataBoundConstructor
  public MesosCloud(
      String nativeLibraryPath,
      String master,
      String description,
      String frameworkName,
      String role,
      double maxCpus,
      int maxMem,
      String slavesUser,
      String credentialsId,
      String principal,
      String secret,
      String slaveDefinitionsName,
      boolean checkpoint,
      boolean onDemandRegistration,
      String jenkinsURL,
      String grafanaDashboardURL,
      String defaultSlaveLabel,
      String schedulerName) throws NumberFormatException {
    super("MesosCloud");

    this.nativeLibraryPath = nativeLibraryPath;
    this.master = master;
    this.description = description;
    this.frameworkName = frameworkName;
    this.role = role;
    this.maxCpus = maxCpus;
    this.maxMem = maxMem;
    this.slavesUser = slavesUser;
    this.credentialsId = credentialsId;
    this.principal = principal;
    this.secret = secret;
    migrateToCredentials();
    this.slaveDefinitionsName = slaveDefinitionsName;
    this.checkpoint = checkpoint;
    this.onDemandRegistration = onDemandRegistration;
    this.setJenkinsURL(jenkinsURL);
    this.grafanaDashboardURL = grafanaDashboardURL;
    this.defaultSlaveLabel = defaultSlaveLabel;
    this.schedulerName = schedulerName;

    if(!onDemandRegistration) {
	    JenkinsScheduler.SUPERVISOR_LOCK.lock();
	    try {
	      restartMesos();
	    } finally {
	      JenkinsScheduler.SUPERVISOR_LOCK.unlock();
	    }
    }
  }

  // Since MesosCloud is used as a key to a Hashmap, we need to set equals/hashcode
  // or lookups won't work if any fields are changed.  Use master string as the key since
  // the rest of this code assumes it is unique among the Cloud objects.
  @Override
  public boolean equals(Object o) {
    if (o == null) { return false; }
    if (o == this) { return true; }
    if (o.getClass() != getClass()) {
      return false;
    }

    MesosCloud that = (MesosCloud) o;
    return new EqualsBuilder()
            .append(master, that.master)
            .append(frameworkName, that.frameworkName)
            .append(schedulerName, that.schedulerName)
            .isEquals();
  }

  @Override
  public int hashCode() {
    return new HashCodeBuilder(11, 57)
            .append(master)
            .append(frameworkName)
            .append(schedulerName)
            .toHashCode();
  }

  public void restartMesos() {
    restartMesos(false);
  }

  public void restartMesos(boolean forceRestart) {

    if(!nativeLibraryLoaded) {
      // First, we attempt to load the library from the given path.
      // If unsuccessful, we attempt to load using 'MesosNativeLibrary.load()'.
      try {
          MesosNativeLibrary.load(nativeLibraryPath);
      } catch (UnsatisfiedLinkError error) {
          LOGGER.warning("Failed to load native Mesos library from '" + nativeLibraryPath +
                         "': " + error.getMessage());
          MesosNativeLibrary.load();
      }
      nativeLibraryLoaded = true;
    }

    // Default to root URL in Jenkins global configuration.
    String jenkinsRootURL = Jenkins.get().getRootUrl();

    // If 'jenkinsURL' parameter is provided in mesos plugin configuration, then that should take precedence.
    if(StringUtils.isNotBlank(jenkinsURL)) {
      jenkinsRootURL = expandJenkinsUrlWithEnvVars();
    }

    // Restart the scheduler if the master has changed or a scheduler is not up. or it is forced to be
    if (!Mesos.getInstance(this).isSchedulerRunning() || forceRestart) {
      if (forceRestart) {
        LOGGER.info("Force the scheduler to restart");
      } else {
        LOGGER.info("Scheduler was down, restarting the scheduler");
      }

      Mesos.getInstance(this).stopScheduler();
      Mesos.getInstance(this).startScheduler(jenkinsRootURL, this);
      requestAMesosSlaveForEveryBuildableItemInQueue();
    } else {
      Mesos.getInstance(this).updateScheduler(jenkinsRootURL, this);
      LOGGER.info("Mesos master has not changed, leaving the scheduler running");
    }

  }

  public void requestAMesosSlaveForEveryBuildableItemInQueue() {
    requestAMesosSlaveForEveryBuildableItem(null);
  }

  /**
   * Requests a Mesos Jenkins agent for every buildable item in the queue depending on the label.
   *
   * @param requestedLabel the label of the buildable items, or null for every label
   */
  private void requestAMesosSlaveForEveryBuildableItem(Label requestedLabel) {
    Jenkins jenkins = Jenkins.get();
    Queue queue = jenkins.getQueue();
    List<Queue.BuildableItem> buildableItems = queue.getBuildableItems();

    for(Queue.BuildableItem buildableItem : buildableItems) {
      Label assignedLabel = buildableItem.getAssignedLabel();
      if (requestedLabel == null || requestedLabel.equals(assignedLabel)) {
        if (canProvision(assignedLabel) && this.isItemForMyFramework(buildableItem)) {
          this.requestNodes(assignedLabel, 1, getFullNameOfItem(buildableItem));
        }
      }
    }
  }

  private String expandJenkinsUrlWithEnvVars() {
    Jenkins instance = Jenkins.get();
    DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties = instance.getGlobalNodeProperties();
    if (globalNodeProperties == null) {
      return jenkinsURL;
    }
    EnvironmentVariablesNodeProperty environmentVariablesNodeProperty = globalNodeProperties.get(EnvironmentVariablesNodeProperty.class);
    if (environmentVariablesNodeProperty == null) {
      return jenkinsURL;
    }
    EnvVars envVars = environmentVariablesNodeProperty.getEnvVars();
    if (envVars != null) {
      return StringUtils.defaultIfBlank(envVars.expand(this.jenkinsURL),instance.getRootUrl());
    } else {
      return jenkinsURL;
    }
  }

  /**
   * Returns the credentials object associated with the stored credentialsId.
   *
   * @return The credentials object associated with the stored credentialsId. May be null if credentialsId is null or
   * if there is no credentials associated with the given id.
   */
  public StandardUsernamePasswordCredentials getCredentials() {
    if (credentialsId == null) {
      return null;
    } else {
      List<DomainRequirement> domainRequirements = (master == null) ? Collections.<DomainRequirement>emptyList()
              : URIRequirementBuilder.fromUri(master.trim()).build();
      Jenkins jenkins = Jenkins.get();
      return CredentialsMatchers.firstOrNull(CredentialsProvider
                      .lookupCredentials(StandardUsernamePasswordCredentials.class, jenkins, ACL.SYSTEM, domainRequirements),
              CredentialsMatchers.withId(credentialsId)
      );
    }
  }

  @Override
  public Collection<PlannedNode> provision(Label label, int excessWorkload) {
    try {
      MesosSlaveInfo mesosSlaveInfo = getSlaveInfo(label);

      if (mesosSlaveInfo != null) {
        if (!mesosSlaveInfo.isUseSlaveOnce()) {
          requestAMesosSlaveForEveryBuildableItem(label);
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error while jenkins tried to provision a Slave for " + label.getDisplayName(), e);
    }

    return new ArrayList<>();
  }

  public void requestNodes(Label label, int excessWorkload, String linkedItem) {
    List<MesosSlaveInfo> slaveInfos = getSlaveInfos();
    final MesosSlaveInfo slaveInfo = getSlaveInfo(slaveInfos, label);

    try {
      while (excessWorkload > 0 && !Jenkins.get().isQuietingDown())  {
        // Start the scheduler if it's not already running.
        if (onDemandRegistration) {
          JenkinsScheduler.SUPERVISOR_LOCK.lock();
          try {
            LOGGER.fine("Checking if scheduler is running");
            if (!Mesos.getInstance(this).isSchedulerRunning()) {
              restartMesos();
            }
          } finally {
            JenkinsScheduler.SUPERVISOR_LOCK.unlock();
          }
        }
        final int numExecutors = Math.min(excessWorkload, slaveInfo.getMaxExecutors());
        excessWorkload -= numExecutors;
        LOGGER.info("Provisioning Jenkins Slave on Mesos with " + numExecutors +
                    " executors. Remaining excess workload: " + excessWorkload + " executors)");


        sendSlaveRequest(numExecutors, slaveInfo, linkedItem);
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Failed to create instances on Mesos", e);
    }
  }

  private long getEstimatedDuration(Job jenkinsJob) {
    if (jenkinsJob == null) {
      LOGGER.warning("Unable to determine estimated duration because job/linked item was not found");
      return 0;
    }

    return jenkinsJob.getEstimatedDuration();
  }

  private String getLastBuildHostname(Job jenkinsJob) {
    if (jenkinsJob == null) {
      LOGGER.warning("Unable to determine hostname of last build because job/linked item was not found");
      return StringUtils.EMPTY;
    }

    MesosBuiltOnProjectAction builtOnProjectAction = jenkinsJob.getAction(MesosBuiltOnProjectAction.class);
    // it could be that there was:
    // * an error when saving the action, try to imitate
    // * not run yet but MesosBuiltOnAction already exists in builds
    // also: benefit of using logic of .getAction() from MesosBuiltOnProjectAction
    if (builtOnProjectAction == null) {
      builtOnProjectAction = new MesosBuiltOnProjectAction(jenkinsJob);
    }

    MesosBuiltOnAction builtOnAction = builtOnProjectAction.getAction();
    if (builtOnAction == null) {
      LOGGER.warning("Unable to determine hostname of last build because last build on action was not found");
      return StringUtils.EMPTY;
    }

    return builtOnAction.getMesosAgentHostname();
  }

  private Job asJob(String linkedItem) {
    try (ACLContext original = ACL.as(ACL.SYSTEM)) {
      return Jenkins.get().getItemByFullName(linkedItem, Job.class);
    }
  }

  private void sendSlaveRequest(int numExecutors, MesosSlaveInfo slaveInfo, String linkedItem) {
    String name = slaveInfo.getLabelString() + "-" + UUID.randomUUID().toString();
    double cpus = slaveInfo.getSlaveCpus() + (numExecutors * slaveInfo.getExecutorCpus());
    double memory = (slaveInfo.getSlaveMem() + (numExecutors * slaveInfo.getExecutorMem())) * (1 + JVM_MEM_OVERHEAD_FACTOR);

    LOGGER.finer("Trying to get additional information from '" + linkedItem + "'");
    Job jenkinsJob = asJob(linkedItem);
    long estimatedDuration = getEstimatedDuration(jenkinsJob);
    String lastBuildHostname = getLastBuildHostname(jenkinsJob);

      // TODO: consider making this configurable (on MesosCloud/Framework level)
    JenkinsSlave.RequestJenkinsSlave jenkinsSlave = new JenkinsSlave.SharedResourcesFirst(
            name, slaveInfo.getLabelString(), numExecutors, linkedItem, lastBuildHostname, estimatedDuration, cpus, memory, slaveInfo.getContainerInfo().getPortMappings(), role);
    LOGGER.finer("Requesting " + jenkinsSlave);

    SlaveRequest slaveRequest = new SlaveRequest(jenkinsSlave, slaveInfo);
    Mesos mesos = Mesos.getInstance(this);

    mesos.startJenkinsSlave(slaveRequest, new SlaveResult(this));
  }

  public void removeSlaveFromJenkins(JenkinsSlave.ResultJenkinsSlave slave) {
    Jenkins jenkins = Jenkins.get();
    Node n = jenkins.getNode(slave.getName());
    if(n != null) {
      Computer computer = n.toComputer();
      if(computer instanceof MesosComputer) {
        MesosComputer mesosComputer = (MesosComputer) computer;
        mesosComputer.deleteSlave();
      }
    }
  }

  public List<MesosSlaveInfo> getSlaveInfos() {
    return SlaveDefinitionsConfiguration.getDescriptorImplStatic().getSlaveInfos(slaveDefinitionsName);
  }

  public boolean isItemForMyFramework(Queue.BuildableItem buildableItem) {
    return canProvision(buildableItem.getAssignedLabel()) && isItemForMyFramework(getFullNameOfItem(buildableItem));
  }

  public boolean isItemForMyFramework(String buildableItem) {
      //MesosFrameworkToItemMapper mesosFrameworkToItemMapper = new MesosFrameworkToItemMapper();
    MesosFrameworkToItemMapper.DescriptorImpl descriptor =
        (MesosFrameworkToItemMapper.DescriptorImpl)Jenkins.get().getDescriptorOrDie(MesosFrameworkToItemMapper.class);
    String foundFramework = descriptor.findFrameworkName(buildableItem);
    return StringUtils.equals(frameworkName, foundFramework);
  }

  public String getFullNameOfItem(Queue.BuildableItem buildableItem) {
    if (buildableItem != null) {
      Queue.Task task = buildableItem.task;
      return getFullNameOfTask(task);
    }

    throw new IllegalArgumentException(Messages.MesosCloud_InvalidItem("(item was null)"));
  }

  @Nonnull
  public String getFullNameOfTask(@Nonnull Queue.Task task) {
    if (task instanceof Item) {
      return ((Item) task).getFullName();
    }

    if(task.equals(task.getOwnerTask())) {
      return task.getFullDisplayName();
    }

    return getFullNameOfTask(task.getOwnerTask());
  }

  @Override
  public boolean canProvision(Label label) {
    // Provisioning is simply creating a task for a jenkins slave.
    // We can provision a Mesos slave as long as the job's label matches any
    // item in the list of configured Mesos labels.
    // TODO(vinod): The framework may not have the resources necessary
    // to start a task when it comes time to launch the slave.
    List<MesosSlaveInfo> slaveInfos = getSlaveInfos();

    if (label != null && slaveInfos != null) {
      for (MesosSlaveInfo slaveInfo : slaveInfos) {
        if (label.matches(Label.parse(slaveInfo.getLabelString()))) {
          return true;
        }
      }
    }
    return false;
  }

  public String getNativeLibraryPath() {
    return this.nativeLibraryPath;
  }

  public void setNativeLibraryPath(String nativeLibraryPath) {
    this.nativeLibraryPath = nativeLibraryPath;
  }

  public String getMaster() {
    return this.master;
  }

  public void setMaster(String master) {
    this.master = master;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getFrameworkName() {
    return frameworkName;
  }

  public void setFrameworkName(String frameworkName) {
    this.frameworkName = frameworkName;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public double getMaxCpus() {
    return maxCpus;
  }

  public void setMaxCpus(double maxCpus) {
    this.maxCpus = maxCpus;
  }

  public int getMaxMem() {
    return maxMem;
  }

  public void setMaxMem(int maxMem) {
    this.maxMem = maxMem;
  }

  public String getSlavesUser() {
    return slavesUser;
  }

  public void setSlavesUser(String slavesUser) {
    this.slavesUser = slavesUser;
  }

  /**
   * @deprecated Use MesosCloud#getCredentials().getUsername() instead.
   * @return
   */
  @Deprecated
  public String getPrincipal() {
    StandardUsernamePasswordCredentials credentials = getCredentials();
    return credentials == null ? "jenkins" : credentials.getUsername();
  }

  /**
   * @deprecated Define credentials and use MesosCloud#setCredentialsId instead.
   * @param principal
   */
  @Deprecated
  public void setPrincipal(String principal) {
    this.principal = principal;
  }

  /**
   * @return The credentialsId to use for this mesos cloud
   */
  public String getCredentialsId() {
    return credentialsId;
  }

  public void setCredentialsId(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  /**
   * @deprecated Use MesosCloud#getCredentials().getPassword() instead.
   * @return
   */
  @Deprecated
  public String getSecret() {
    StandardUsernamePasswordCredentials credentials = getCredentials();
    return credentials == null ? "" : Secret.toString(credentials.getPassword());
  }

  /**
   * @deprecated Define credentials and use MesosCloud#setCredentialsId instead.
   * @param secret
   */
  @Deprecated
  public void setSecret(String secret) {
    this.secret = secret;
  }

  public boolean isOnDemandRegistration() {
    return onDemandRegistration;
  }

  public void setOnDemandRegistration(boolean onDemandRegistration) {
    this.onDemandRegistration = onDemandRegistration;
  }

  public String getGrafanaDashboardURL() {
    return grafanaDashboardURL;
  }

  public String getSlaveDefinitionsName() {
    return slaveDefinitionsName;
  }

  public String getDefaultSlaveLabel() {
    return defaultSlaveLabel;
  }

  @Override
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl) super.getDescriptor();
  }

  public static MesosCloud get() {
    return Jenkins.get().clouds.get(MesosCloud.class);
  }

  /**
  * @return the checkpoint
  */
  public boolean isCheckpoint() {
    return checkpoint;
  }

  public MesosSlaveInfo getSlaveInfo(String label) {
    List<MesosSlaveInfo> slaveInfos = getSlaveInfos();

    for (MesosSlaveInfo slaveInfo : slaveInfos) {
      if (label.equals(slaveInfo.getLabelString())) {
        return slaveInfo;
      }
    }
    return null;
  }

  public MesosSlaveInfo getSlaveInfo(Label label) {
    return getSlaveInfo(getSlaveInfos(), label);
  }

  public MesosSlaveInfo getSlaveInfo(List<MesosSlaveInfo> slaveInfos,
      Label label) {
    for (MesosSlaveInfo slaveInfo : slaveInfos) {
      if (label.matches(Label.parse(slaveInfo.getLabelString()))) {
        return slaveInfo;
      }
    }
    return null;
  }

  /**
  * Retrieves the slaveattribute corresponding to label name.
  *
  * @param labelName The Jenkins label name.
  * @return slaveattribute as a JSONObject.
  */

  public JSONObject getSlaveAttributeForLabel(String labelName) {
    if(labelName!=null) {
      List<MesosSlaveInfo> slaveInfos = getSlaveInfos();

      for (MesosSlaveInfo slaveInfo : slaveInfos) {
        if (labelName.equals(slaveInfo.getLabelString())) {
          return slaveInfo.getSlaveAttributes();
        }
      }
    }
    return null;
  }

  protected Object readResolve() {
    migrateToCredentials();
    if (role == null) {
      role = "*";
    }
    return this;
  }

  /**
   * Migrate principal/secret to credentials
   */
  private void migrateToCredentials() {
    if (principal != null) {
      List<DomainRequirement> domainRequirements = (master == null) ? Collections.<DomainRequirement>emptyList()
        : URIRequirementBuilder.fromUri(master.trim()).build();
      Jenkins jenkins = Jenkins.get();
      // Look up existing credentials with the same username.
      List<StandardUsernamePasswordCredentials> credentials = CredentialsMatchers.filter(CredentialsProvider
        .lookupCredentials(StandardUsernamePasswordCredentials.class, jenkins, ACL.SYSTEM, domainRequirements),
        CredentialsMatchers.withUsername(principal)
      );
      for (StandardUsernamePasswordCredentials cred: credentials) {
        if (StringUtils.equals(secret, Secret.toString(cred.getPassword()))) {
          // If some credentials have the same username/password, use those.
          this.credentialsId = cred.getId();
          break;
        }
      }
      if (credentialsId == null) {
        // If we couldn't find any existing credentials,
        // create new credentials with the principal and secret and use it.
        StandardUsernamePasswordCredentials newCredentials = new UsernamePasswordCredentialsImpl(
          CredentialsScope.SYSTEM, null, null, principal, secret);
        SystemCredentialsProvider.getInstance().getCredentials().add(newCredentials);
        this.credentialsId = newCredentials.getId();
      }
      principal = null;
      secret = null;
    }
  }

  public String getJenkinsURL() {
	  return jenkinsURL;
  }

  public void setJenkinsURL(String jenkinsURL) {
	this.jenkinsURL = jenkinsURL;
}

  public String getSchedulerName() {
    if (StringUtils.isBlank(schedulerName)) {
      schedulerName = JenkinsSchedulerNew.NAME;
    }
    return schedulerName;
  }

  @Extension
  @SuppressFBWarnings
  public static class DescriptorImpl extends Descriptor<Cloud> {


    @Nonnull
    @Override
    public String getDisplayName() {
      return "Mesos Cloud";
    }

    @Restricted(DoNotUse.class) // Stapler only.
    @SuppressWarnings("unused") // Used by stapler.
    @RequirePOST
    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String master) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      List<DomainRequirement> domainRequirements = (master == null) ? Collections.<DomainRequirement>emptyList()
        : URIRequirementBuilder.fromUri(master.trim()).build();
      return new StandardListBoxModel().withEmptySelection().withMatching(
        CredentialsMatchers.instanceOf(UsernamePasswordCredentials.class),
        CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, item, null, domainRequirements)
      );
    }

    /**
     * Test connection from configuration page.
     *
     * @param master url of the Jenkins master
     * @param nativeLibraryPath path to the native library on the Jenkins master
     * @return whether or not the connection test succeeded
     */
    @RequirePOST
    @SuppressWarnings("unused")
    public FormValidation doTestConnection(
        @QueryParameter("master") String master,
        @QueryParameter("nativeLibraryPath") String nativeLibraryPath) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      master = master.trim();

      if (master.equals("local")) {
        return FormValidation.warning("'local' creates a local mesos cluster");
      }

      if (master.startsWith("zk://")) {
        return FormValidation.warning("Zookeeper paths can be used, but the connection cannot be " +
            "tested prior to saving this page.");
      }

      if (master.startsWith("http://")) {
        return FormValidation.error("Please omit 'http://'.");
      }

      if (!nativeLibraryPath.startsWith("/")) {
        return FormValidation.error("Please provide an absolute path");
      }

      try {
        // URL requires the protocol to be explicitly specified.
        HttpURLConnection urlConn =
          (HttpURLConnection) new URL("http://" + master).openConnection();
        urlConn.connect();
        int code = urlConn.getResponseCode();
        urlConn.disconnect();

        if (code == 200) {
          return FormValidation.ok("Connected to Mesos successfully");
        } else {
          return FormValidation.error("Status returned from url was " + code);
        }
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "Failed to connect to Mesos " + master, e);
        return FormValidation.error(e.getMessage());
      }
    }

    @SuppressWarnings("unused")
    public FormValidation doCheckMaxExecutors(@QueryParameter("maxExecutors") final String strMaxExecutors,
                                              @QueryParameter("useSlaveOnce") final String strUseSlaveOnce) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      int maxExecutors;
      boolean useSlaveOnce;
      try {
        maxExecutors = Integer.parseInt(strMaxExecutors);
        useSlaveOnce = Boolean.parseBoolean(strUseSlaveOnce);
      } catch (Exception e) {
        return FormValidation.ok();
      }

      if(useSlaveOnce && (maxExecutors > 1)) return FormValidation.error("A UseSlaveOnce Slave can have at least 1 executor.");
      return FormValidation.ok();
    }

    @SuppressWarnings("unused")
    public FormValidation doCheckSlaveCpus(@QueryParameter String value) {
      return doCheckCpus(value);
    }

    @SuppressWarnings("unused")
    public FormValidation doCheckExecutorCpus(@QueryParameter String value) {
      return doCheckCpus(value);
    }

    private FormValidation doCheckCpus(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      boolean valid = true;
      String errorMessage = "Invalid CPUs value, it should be a positive decimal.";

      if (StringUtils.isBlank(value)) {
        valid = false;
      } else {
        try {
          if (Double.parseDouble(value) < 0) {
            valid = false;
          }
        } catch (NumberFormatException e) {
          valid = false;
        }
      }
      return valid ? FormValidation.ok() : FormValidation.error(errorMessage);
    }

    @SuppressWarnings("unused")
    public FormValidation doCheckRemoteFSRoot(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      String errorMessage = "Invalid Remote FS Root - should be non-empty. It will be defaulted to \"jenkins\".";

      return StringUtils.isNotBlank(value) ? FormValidation.ok() : FormValidation.error(errorMessage);
    }

    @Restricted(DoNotUse.class) // Stapler only.
    @SuppressWarnings("unused") // Used by stapler.
    @RequirePOST
    public ListBoxModel doFillSlaveDefinitionsNameItems(@QueryParameter String selection) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      ListBoxModel slaveDefinitionsNamesItems = new ListBoxModel();

      List<MesosSlaveDefinitions> slaveDefinitionsEntries =
        SlaveDefinitionsConfiguration.getDescriptorImplStatic().getSlaveDefinitionsEntries();

      for (MesosSlaveDefinitions slaveDefinitionsEntry : slaveDefinitionsEntries) {
        String currentSlaveDefinitionsName = slaveDefinitionsEntry.getDefinitionsName();

        boolean selected = StringUtils.equals(currentSlaveDefinitionsName, selection);
        slaveDefinitionsNamesItems.add(new ListBoxModel.Option(
            currentSlaveDefinitionsName,
            currentSlaveDefinitionsName,
            selected
        ));
      }

      return slaveDefinitionsNamesItems;
    }

    @Restricted(DoNotUse.class) // Stapler only.
    @SuppressWarnings("unused") // Used by stapler.
    @RequirePOST
    public ListBoxModel doFillDefaultSlaveLabelItems(@QueryParameter String slaveDefinitionsName, @QueryParameter String selection) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      ListBoxModel defaultSlaveLabelItems = new ListBoxModel();
      defaultSlaveLabelItems.add(DEFAULT_SLAVE_LABEL_NONE, DEFAULT_SLAVE_LABEL_NONE);

      List<MesosSlaveInfo> mesosSlaveInfos = SlaveDefinitionsConfiguration.getDescriptorImplStatic().getSlaveInfos(slaveDefinitionsName);

      if (mesosSlaveInfos != null) {
        for (MesosSlaveInfo mesosSlaveInfo : mesosSlaveInfos) {
          String currentMesosSlaveInfoName = mesosSlaveInfo.getLabelString();
          boolean selected = StringUtils.equals(currentMesosSlaveInfoName, selection);
          defaultSlaveLabelItems.add(new ListBoxModel.Option(
                  currentMesosSlaveInfoName,
                  currentMesosSlaveInfoName,
                  selected
          ));
        }
      }

      return defaultSlaveLabelItems;
    }

  }
}
