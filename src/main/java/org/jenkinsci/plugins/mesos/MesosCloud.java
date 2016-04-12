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
import hudson.model.Node.Mode;
import hudson.slaves.*;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.mesos.MesosNativeLibrary;
import org.jenkinsci.plugins.mesos.config.acl.MesosFrameworkToItemMapper;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveDefinitions;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.SlaveDefinitionsConfiguration;
import org.jenkinsci.plugins.mesos.monitoring.MesosTaskFailureMonitor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

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
  private String slavesUser;
  private String principal;
  private String secret;
  private final boolean checkpoint; // Set true to enable checkpointing. False by default.
  private boolean onDemandRegistration; // If set true, this framework disconnects when there are no builds in the queue and re-registers when there are.
  private String jenkinsURL;
  private List<PlannedNode> plannedNodeList;
  private String grafanaDashboardURL;

  // Find the default values for these variables in
  // src/main/resources/org/jenkinsci/plugins/mesos/MesosCloud/config.jelly.
  private List<MesosSlaveInfo> slaveInfos;

  private static String staticMaster;

  private static final Logger LOGGER = Logger.getLogger(MesosCloud.class.getName());

  // We allocate 10% more memory to the Mesos task to account for the JVM overhead.
  private static final double JVM_MEM_OVERHEAD_FACTOR = 0.1;

  private static volatile boolean nativeLibraryLoaded = false;

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
      String slavesUser,
      String principal,
      String secret,
      List<MesosSlaveInfo> slaveInfos,
      boolean checkpoint,
      boolean onDemandRegistration,
      String jenkinsURL,
      String grafanaDashboardURL) throws NumberFormatException {
    super("MesosCloud");

    this.nativeLibraryPath = nativeLibraryPath;
    this.master = master;
    this.description = description;
    this.frameworkName = frameworkName;
    this.slavesUser = slavesUser;
    this.principal = principal;
    this.secret = secret;
    this.slaveInfos = slaveInfos;
    this.checkpoint = checkpoint;
    this.onDemandRegistration = onDemandRegistration;
    this.setJenkinsURL(jenkinsURL);
    this.grafanaDashboardURL = grafanaDashboardURL;
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
    plannedNodeList = new ArrayList<PlannedNode>();

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
    if (!master.equals(staticMaster) || !Mesos.getInstance(this).isSchedulerRunning() || forceRestart) {
      if (!master.equals(staticMaster)) {
        LOGGER.info("Mesos master changed, restarting the scheduler");
        staticMaster = master;
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

  private int getBuildsInQueueMatchinLabelCount(Label label) {
    Queue queue = Queue.getInstance();
    return queue.countBuildableItemsFor(label);
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
      LOGGER.fine("Error while jenkins tried to provision a Slave for "+label.getDisplayName());
      e.printStackTrace();
    }

    return new ArrayList<PlannedNode>();
  }

  public void requestNodes(Label label, int excessWorkload, String linkedItem) {
    List<PlannedNode> list = new ArrayList<PlannedNode>();
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

  private void sendSlaveRequest(int numExecutors, MesosSlaveInfo slaveInfo, String linkedItem) throws Descriptor.FormException, IOException {
    String name = slaveInfo.getLabelString() + "-" + UUID.randomUUID().toString();
    double cpus = slaveInfo.getSlaveCpus() + (numExecutors * slaveInfo.getExecutorCpus());
    int memory = (int)((slaveInfo.getSlaveMem() + (numExecutors * slaveInfo.getExecutorMem())) * (1 + JVM_MEM_OVERHEAD_FACTOR));


    Mesos.JenkinsSlave jenkinsSlave = new Mesos.JenkinsSlave(name,slaveInfo.getLabelString(), numExecutors, linkedItem);
    Mesos.SlaveRequest slaveRequest = new Mesos.SlaveRequest(jenkinsSlave, cpus, memory, slaveInfo);
    Mesos mesos = Mesos.getInstance(this);

    mesos.startJenkinsSlave(slaveRequest, new Mesos.SlaveResult() {
      @Override
      public void running(Mesos.JenkinsSlave slave) {

      }

      @Override
      public void finished(Mesos.JenkinsSlave slave) {
        //yeah, the task finishe. so we can remove the slave from Jenkins instance
        LOGGER.info(String.format("remove finished Node %s from Jenkins", slave.getName()));
        removeSlaveFromJenkins(slave);
      }

      @Override
      public void failed(Mesos.JenkinsSlave slave) {
        try {
          MesosTaskFailureMonitor.getInstance().addFailedSlave(slave);
        } catch (Exception e) {
          LOGGER.fine("Error while getting MesosTaskFailureMonitor " + e.getMessage());
          e.printStackTrace();
        }
      }
    });
  }

  private void removeSlaveFromJenkins(Mesos.JenkinsSlave slave) {
    try {
      Jenkins jenkins = Jenkins.getInstance();
      Node n = jenkins.getNode(slave.getName());
      if(n != null) {
        jenkins.removeNode(n);
      }
    } catch (IOException e) {
      LOGGER.fine("Error while removing Slave from Jenkins " + e.getMessage());
      e.printStackTrace();
    }
  }

  public List<MesosSlaveInfo> getSlaveInfos() {
    return slaveInfos;
  }

  public void setSlaveInfos(List<MesosSlaveInfo> slaveInfos) {
    this.slaveInfos = slaveInfos;
  }

  public boolean isItemForMyFramework(Queue.BuildableItem buildableItem) {
    if (canProvision(buildableItem.getAssignedLabel())) {
      return isItemForMyFramework(getFullNameOfItem(buildableItem));
    }
    return false;
  }

  public boolean isItemForMyFramework(String buildableItem) {
      String foundFramework = "";
      MesosFrameworkToItemMapper mesosFrameworkToItemMapper = new MesosFrameworkToItemMapper();
      foundFramework = mesosFrameworkToItemMapper.findFrameworkName(buildableItem);
      return this.frameworkName.equals(foundFramework);
  }

  public String getFullNameOfItem(Queue.BuildableItem buildableItem) {
    if(buildableItem != null && buildableItem.task instanceof Project) {
      Project project = (Project) buildableItem.task;
      return project.getFullName();
    }

    return null;
  }

  @Override
  public boolean canProvision(Label label) {
    // Provisioning is simply creating a task for a jenkins slave.
    // We can provision a Mesos slave as long as the job's label matches any
    // item in the list of configured Mesos labels.
    // TODO(vinod): The framework may not have the resources necessary
    // to start a task when it comes time to launch the slave.
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

  @Override
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl) super.getDescriptor();
  }

  public static MesosCloud get() {
    return Hudson.getInstance().clouds.get(MesosCloud.class);
  }

  /**
  * @return the checkpoint
  */
  public boolean isCheckpoint() {
    return checkpoint;
  }

  public MesosSlaveInfo getSlaveInfo(String label) {
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
    private String slavesUser;
    private String principal;
    private String secret;
    private String slaveAttributes;
    private boolean checkpoint;
    private String jenkinsURL;
    private int provisioningThreshold;
    private List<MesosSlaveInfo> slaveInfos;
    private String grafanaDashboardURL;

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
      principal = object.getString("principal");
      secret = object.getString("secret");
      slaveAttributes = object.getString("slaveAttributes");
      checkpoint = object.getBoolean("checkpoint");
      jenkinsURL = object.getString("jenkinsURL");
      grafanaDashboardURL = object.getString("grafanaDashboardURL");
      provisioningThreshold = object.getInt("provisioningThreshold");
      slavesUser = object.getString("slavesUser");
      slaveInfos = new ArrayList<MesosSlaveInfo>();
      JSONArray labels = object.getJSONArray("slaveInfos");
      if (labels != null) {
        for (int i = 0; i < labels.size(); i++) {
          JSONObject label = labels.getJSONObject(i);
          if (label != null) {
            MesosSlaveInfo.ExternalContainerInfo externalContainerInfo = null;
            if (label.has("externalContainerInfo")) {
              JSONObject externalContainerInfoJson = label
                  .getJSONObject("externalContainerInfo");
              externalContainerInfo = new MesosSlaveInfo.ExternalContainerInfo(
                  externalContainerInfoJson.getString("image"),
                  externalContainerInfoJson.getString("options"));
            }

            MesosSlaveInfo.ContainerInfo containerInfo = null;
            if (label.has("containerInfo")) {
              JSONObject containerInfoJson = label
                  .getJSONObject("containerInfo");
              List<MesosSlaveInfo.Volume> volumes = new ArrayList<MesosSlaveInfo.Volume>();
              if (containerInfoJson.has("volumes")) {
                JSONArray volumesJson = containerInfoJson
                    .getJSONArray("volumes");
                for (Object obj : volumesJson) {
                  JSONObject volumeJson = (JSONObject) obj;
                  volumes
                      .add(new MesosSlaveInfo.Volume(volumeJson
                          .getString("containerPath"), volumeJson
                          .getString("hostPath"), volumeJson
                          .getBoolean("readOnly")));
                }
              }

              List<MesosSlaveInfo.Parameter> parameters = new ArrayList<MesosSlaveInfo.Parameter>();

              if (containerInfoJson.has("parameters")) {
                JSONArray parametersJson = containerInfoJson.getJSONArray("parameters");
                for (Object obj : parametersJson) {
                  JSONObject parameterJson = (JSONObject) obj;
                  parameters.add(new MesosSlaveInfo.Parameter(parameterJson.getString("key"), parameterJson.getString("value")));
                }
              }

              List<MesosSlaveInfo.PortMapping> portMappings = new ArrayList<MesosSlaveInfo.PortMapping>();

              final String networking = containerInfoJson.getString("networking");
              if (Network.BRIDGE.equals(Network.valueOf(networking)) && containerInfoJson.has("portMappings")) {
                JSONArray portMappingsJson = containerInfoJson
                    .getJSONArray("portMappings");
                for (Object obj : portMappingsJson) {
                  JSONObject portMappingJson = (JSONObject) obj;
                  portMappings.add(new MesosSlaveInfo.PortMapping(
                          portMappingJson.getInt("containerPort"),
                          portMappingJson.getInt("hostPort"),
                          portMappingJson.getString("protocol"),
                          portMappingJson.getString("description"),
                          portMappingJson.getString("urlFormat")
                  ));
                }
              }

              containerInfo = new MesosSlaveInfo.ContainerInfo(
                  containerInfoJson.getString("type"),
                  containerInfoJson.getString("dockerImage"),
                  containerInfoJson.getBoolean("dockerPrivilegedMode"),
                  containerInfoJson.getBoolean("dockerForcePullImage"),
                  containerInfoJson.getBoolean("useCustomDockerCommandShell"),
                  containerInfoJson.getString ("customDockerCommandShell"),
                  volumes,
                  parameters,
                  networking,
                  portMappings);
            }

            MesosSlaveInfo.RunAsUserInfo runAsUserInfo = null;
              if (label.has("runAsUserInfo")) {
                JSONObject runAsUserInfoJson = label.getJSONObject("runAsUserInfo");
                runAsUserInfo = new MesosSlaveInfo.RunAsUserInfo(
                        runAsUserInfoJson.getString("username"),
                        runAsUserInfoJson.getString("command")
                        );
            }

            List<MesosSlaveInfo.URI> additionalURIs = new ArrayList<MesosSlaveInfo.URI>();
            if (label.has("additionalURIs")) {
              JSONArray additionalURIsJson = label.getJSONArray("additionalURIs");
              for (Object obj : additionalURIsJson) {
                JSONObject URIJson = (JSONObject) obj;
                additionalURIs.add(new MesosSlaveInfo.URI(
                    URIJson.getString("value"),
                    URIJson.getBoolean("executable"),
                    URIJson.getBoolean("extract")));
              }
            }


            List<MesosSlaveInfo.Command> additionalCommands = new ArrayList<MesosSlaveInfo.Command>();
            if (label.has("additionalCommands")) {
              JSONArray additionalCommandsJson = label.getJSONArray("additionalCommands");
              for (Object obj : additionalCommandsJson) {
                JSONObject additionalCommandJson = (JSONObject) obj;
                additionalCommands.add(new MesosSlaveInfo.Command(
                        additionalCommandJson.getString("value")));
              }
            }

            MesosSlaveInfo slaveInfo = new MesosSlaveInfo(
                object.getString("labelString"),
                (Mode) object.get("mode"),
                object.getString("slaveCpus"),
                object.getString("slaveMem"),
                object.getString("maxExecutors"),
                object.getString("executorCpus"),
                object.getString("executorMem"),
                object.getString("remoteFSRoot"),
                object.getString("idleTerminationMinutes"),
                object.getString("maximumTimeToLiveMinutes"),
                object.getBoolean("useSlaveOnce"),
                object.getString("slaveAttributes"),
                object.getString("jvmArgs"),
                object.getString("jnlpArgs"),
                externalContainerInfo,
                containerInfo,
                additionalURIs,
                runAsUserInfo,
                additionalCommands);
            slaveInfos.add(slaveInfo);
          }
        }
      }
      save();
      return super.configure(request, object);
    }

    /**
     * Test connection from configuration page.
     */
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

    public FormValidation doCheckMaxExecutors(@QueryParameter("maxExecutors") final String strMaxExecutors,
                                              @QueryParameter("useSlaveOnce") final String strUseSlaveOnce) {
      int maxExecutors=1;
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

    public FormValidation doCheckSlaveCpus(@QueryParameter String value) {
      return doCheckCpus(value);
    }

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

    public FormValidation doCheckRemoteFSRoot(@QueryParameter String value) {
      String errorMessage = "Invalid Remote FS Root - should be non-empty. It will be defaulted to \"jenkins\".";

      return StringUtils.isNotBlank(value) ? FormValidation.ok() : FormValidation.error(errorMessage);
    }
  }
}
