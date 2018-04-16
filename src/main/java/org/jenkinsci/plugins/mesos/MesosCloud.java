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

import hudson.EnvVars;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.*;
import hudson.slaves.Cloud;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
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
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
  private String principal;
  private String secret;
  private final boolean checkpoint; // Set true to enable checkpointing. False by default.
  private boolean onDemandRegistration; // If set true, this framework disconnects when there are no builds in the queue and re-registers when there are.
  private String jenkinsURL;
  private String grafanaDashboardURL;

  // Find the default values for these variables in
  // src/main/resources/org/jenkinsci/plugins/mesos/MesosCloud/config.jelly.
  private String slaveDefinitionsName;

  private String defaultSlaveLabel;

  private static final Logger LOGGER = Logger.getLogger(MesosCloud.class.getName());

  // We allocate 10% more memory to the Mesos task to account for the JVM overhead.
  private static final double JVM_MEM_OVERHEAD_FACTOR = 0.1;

  private static volatile boolean nativeLibraryLoaded = false;

  public static final String DEFAULT_SLAVE_LABEL_NONE = "None";

  /**
   * We want to start the Mesos scheduler as part of the initialization of Jenkins
   * and after the cloud class values have been restored from persistence.If this is
   * the very first time, this method will be NOOP as MesosCloud is not registered yet.
   */

  @Initializer(after=InitMilestone.JOB_LOADED)
  public static void init() {
    Jenkins jenkins = Jenkins.getInstance();
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
      String principal,
      String secret,
      String slaveDefinitionsName,
      boolean checkpoint,
      boolean onDemandRegistration,
      String jenkinsURL,
      String grafanaDashboardURL,
      String defaultSlaveLabel) throws NumberFormatException {
    super("MesosCloud");

    this.nativeLibraryPath = nativeLibraryPath;
    this.master = master;
    this.description = description;
    this.frameworkName = frameworkName;
    this.role = role;
    this.maxCpus = maxCpus;
    this.maxMem = maxMem;
    this.slavesUser = slavesUser;
    this.principal = principal;
    this.secret = secret;
    this.slaveDefinitionsName = slaveDefinitionsName;
    this.checkpoint = checkpoint;
    this.onDemandRegistration = onDemandRegistration;
    this.setJenkinsURL(jenkinsURL);
    this.grafanaDashboardURL = grafanaDashboardURL;
    this.defaultSlaveLabel = defaultSlaveLabel;

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
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    MesosCloud that = (MesosCloud) o;

    if (master != null ? !master.equals(that.master) : that.master != null) return false;
    if (frameworkName != null ? !frameworkName.equals(that.frameworkName) : that.frameworkName != null) return false;

    return true;
  }

  @Override
  public int hashCode() {
    return master != null ? master.hashCode() : 0;
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
    String jenkinsRootURL = Jenkins.getInstance().getRootUrl();

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
    Jenkins jenkins = Jenkins.getInstance();
    for(Queue.BuildableItem buildableItem : jenkins.getQueue().getBuildableItems()) {
      Label label = buildableItem.getAssignedLabel();
      if(canProvision(label) && this.isItemForMyFramework(buildableItem)) {
        this.requestNodes(label, 1, getFullNameOfItem(buildableItem));
      }
    }
  }

  private String expandJenkinsUrlWithEnvVars() {
    Jenkins instance = Jenkins.getInstance();
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

  @Override
  public Collection<PlannedNode> provision(Label label, int excessWorkload) {
    try {
      MesosSlaveInfo mesosSlaveInfo = getSlaveInfo(label);

      if (mesosSlaveInfo != null) {
        if (!mesosSlaveInfo.isUseSlaveOnce()) {
          Queue queue = Queue.getInstance();
          int buildablesInQueueCount = queue.countBuildableItemsFor(label);

          //we need the scheduler to get known requests for the label.
          JenkinsScheduler jenkinsScheduler = (JenkinsScheduler) Mesos.getInstance(this).getScheduler();
          //our "softlimit" for provisioning slaves. Jenkins shouldn't provision more slaves than needet.
          int requestsForLabelCount = jenkinsScheduler.getRequestsMatchingLabel(label).size();

          int legalWorkload = Math.min(excessWorkload, (buildablesInQueueCount - requestsForLabelCount));

          if (legalWorkload > 0) {
            requestNodes(label, legalWorkload, null);
          } else {
            LOGGER.info("Ignore Jenkins provisioning request. There are enough requests to mesos (legalWorkLoad: " + legalWorkload+")");
          }
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error while jenkins tried to provision a Slave for " + label.getDisplayName(), e);
    }

    return new ArrayList<PlannedNode>();
  }

  public void requestNodes(Label label, int excessWorkload, String linkedItem) {
    List<MesosSlaveInfo> slaveInfos = getSlaveInfos();
    final MesosSlaveInfo slaveInfo = getSlaveInfo(slaveInfos, label);

    try {
      while (excessWorkload > 0 && !Jenkins.getInstance().isQuietingDown())  {
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

  private void sendSlaveRequest(int numExecutors, MesosSlaveInfo slaveInfo, String linkedItem) throws Descriptor.FormException, IOException {
    String name = slaveInfo.getLabelString() + "-" + UUID.randomUUID().toString();
    double cpus = slaveInfo.getSlaveCpus() + (numExecutors * slaveInfo.getExecutorCpus());
    double memory = (slaveInfo.getSlaveMem() + (numExecutors * slaveInfo.getExecutorMem())) * (1 + JVM_MEM_OVERHEAD_FACTOR);

    LOGGER.finer("Trying to get additional information from '" + linkedItem + "'");
    Job jenkinsJob = (Job)Jenkins.getInstance().getItemByFullName(linkedItem);
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
    Jenkins jenkins = Jenkins.getInstance();
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
        (MesosFrameworkToItemMapper.DescriptorImpl)Jenkins.getInstance().getDescriptorOrDie(MesosFrameworkToItemMapper.class);
    String foundFramework = descriptor.findFrameworkName(buildableItem);
    return StringUtils.equals(frameworkName, foundFramework);
  }

  public String getFullNameOfItem(Queue.BuildableItem buildableItem) {
    if (buildableItem != null) {
      Queue.Task task = buildableItem.task;
      return getFullNameOfTask(task);
    }

    throw new IllegalArgumentException(Messages.MesosCloud_InvalidItem(buildableItem));
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

  public String getPrincipal() {
        return principal;
    }

  public void setPrincipal(String principal) {
        this.principal = principal;
    }

  public String getSecret() {
        return secret;
    }

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
    return Jenkins.getInstance().clouds.get(MesosCloud.class);
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

  public String getJenkinsURL() {
	  return jenkinsURL;
  }

  public void setJenkinsURL(String jenkinsURL) {
	this.jenkinsURL = jenkinsURL;
}

  @Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {
    private String nativeLibraryPath;
    private String master;
    private String description;
    private String frameworkName;
    private String role;
    private double maxCpus;
    private int maxMem;
    private String slavesUser;
    private String principal;
    private String secret;
    private String slaveAttributes;
    private boolean checkpoint;
    private String jenkinsURL;
    private int provisioningThreshold;
    private String slaveDefinitionsName;
    private String grafanaDashboardURL;
    private String defaultSlaveLabel;

    @Override
    public String getDisplayName() {
      return "Mesos Cloud";
    }

    @Override
    public boolean configure(StaplerRequest request, JSONObject object)
        throws FormException {
      LOGGER.info(object.toString());
      nativeLibraryPath = object.getString("nativeLibraryPath");
      master = object.getString("master");
      description = object.getString("description");
      frameworkName = object.getString("frameworkName");
      role = object.getString("role");
      maxCpus = object.getDouble("maxCpus");
      maxMem = object.getInt("maxMem");
      principal = object.getString("principal");
      secret = object.getString("secret");
      slaveAttributes = object.getString("slaveAttributes");
      checkpoint = object.getBoolean("checkpoint");
      jenkinsURL = object.getString("jenkinsURL");
      grafanaDashboardURL = object.getString("grafanaDashboardURL");
      provisioningThreshold = object.getInt("provisioningThreshold");
      slavesUser = object.getString("slavesUser");
      slaveDefinitionsName = object.getString("slaveDefinitionsName");
      defaultSlaveLabel = object.getString("defaultSlaveLabel");

      save();
      return super.configure(request, object);
    }

    /**
     * Test connection from configuration page.
     */
    @SuppressWarnings("unused")
    public FormValidation doTestConnection(
        @QueryParameter("master") String master,
        @QueryParameter("nativeLibraryPath") String nativeLibraryPath)
        throws IOException, ServletException {
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
      String errorMessage = "Invalid Remote FS Root - should be non-empty. It will be defaulted to \"jenkins\".";

      return StringUtils.isNotBlank(value) ? FormValidation.ok() : FormValidation.error(errorMessage);
    }

    @SuppressWarnings("unused")
    public ListBoxModel doFillSlaveDefinitionsNameItems() {
      ListBoxModel slaveDefinitionsNamesItems = new ListBoxModel();

      List<MesosSlaveDefinitions> slaveDefinitionsEntries =
        SlaveDefinitionsConfiguration.getDescriptorImplStatic().getSlaveDefinitionsEntries();

      for (MesosSlaveDefinitions slaveDefinitionsEntry : slaveDefinitionsEntries) {
        String currentSlaveDefinitionsName = slaveDefinitionsEntry.getDefinitionsName();
        boolean selected = StringUtils.equals(currentSlaveDefinitionsName, slaveDefinitionsName);
        slaveDefinitionsNamesItems.add(new ListBoxModel.Option(
            currentSlaveDefinitionsName,
            currentSlaveDefinitionsName,
            selected
        ));
      }

      return slaveDefinitionsNamesItems;
    }

    @SuppressWarnings("unused")
    public ListBoxModel doFillDefaultSlaveLabelItems(@QueryParameter String slaveDefinitionsName) {

      ListBoxModel defaultSlaveLabelItems = new ListBoxModel();
      defaultSlaveLabelItems.add(DEFAULT_SLAVE_LABEL_NONE, DEFAULT_SLAVE_LABEL_NONE);

      List<MesosSlaveInfo> mesosSlaveInfos = SlaveDefinitionsConfiguration.getDescriptorImplStatic().getSlaveInfos(slaveDefinitionsName);

      if (mesosSlaveInfos != null) {
        for (MesosSlaveInfo mesosSlaveInfo : mesosSlaveInfos) {
          String currentMesosSlaveInfoName = mesosSlaveInfo.getLabelString();
          boolean selected = StringUtils.equals(currentMesosSlaveInfoName, defaultSlaveLabel);
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
