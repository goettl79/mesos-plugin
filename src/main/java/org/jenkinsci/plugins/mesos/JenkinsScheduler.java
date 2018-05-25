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


import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos.*;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.PortMapping;
import org.apache.mesos.Protos.Value.Range;
import org.apache.mesos.Protos.Volume.Mode;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JenkinsScheduler implements Scheduler {
  private static final String ITEM_FULLNAME_TOKEN  = "${ITEM_FULLNAME}";
  private static final String FRAMEWORK_NAME_TOKEN = "${FRAMEWORK_NAME}";
  private static final String JENKINS_MASTER_TOKEN = "${JENKINS_MASTER}";

  private static final double DEFAULT_DECLINE_OFFER_DURATION = TimeUnit.MINUTES.toSeconds(10);
  private static final double DEFAULT_FAILOVER_TIMEOUT = TimeUnit.DAYS.toSeconds(7);

  private static final String SLAVE_JAR_URI_SUFFIX = "jnlpJars/slave.jar";
  private static final String SLAVE_REQUEST_FORMAT="mesos/createSlave/%s";

  private static final String SLAVE_COMMAND_FORMAT =
      "java -DHUDSON_HOME=jenkins -server -Xmx%dm %s -jar ${MESOS_SANDBOX-.}/slave.jar -noReconnect %s %s -jnlpUrl %s";
  private static final String JNLP_SECRET_FORMAT = "-secret %s";
  public static final String PORT_RESOURCE_NAME = "ports";

  private Queue<Request> requests;
  private Map<TaskID, Result> results;
  private Set<TaskID> finishedTasks;
  private volatile SchedulerDriver driver;
  private String jenkinsMaster;
  private volatile MesosCloud mesosCloud;
  private volatile boolean running;

  private static final Logger LOGGER = Logger.getLogger(JenkinsScheduler.class.getName());

  public static final Lock SUPERVISOR_LOCK = new ReentrantLock();

  public JenkinsScheduler(String jenkinsMaster, MesosCloud mesosCloud) {
    LOGGER.info("JenkinsScheduler instantiated with jenkins " + jenkinsMaster + " and mesos " + mesosCloud.getMaster());

    this.jenkinsMaster = jenkinsMaster;
    this.mesosCloud = mesosCloud;

    requests = new LinkedList<Request>();
    results = new HashMap<TaskID, Result>();
    finishedTasks = Collections.newSetFromMap(new ConcurrentHashMap<TaskID, Boolean>());
  }

  public synchronized void init() {
    // This is to ensure that isRunning() returns true even when the driver is not yet inside run().
    // This is important because MesosCloud.provision() starts a new framework whenever isRunning() is false.
    running = true;
    String targetUser = mesosCloud.getSlavesUser();
    String webUrl = Jenkins.getInstance().getRootUrl();
    if (webUrl == null) webUrl = System.getenv("JENKINS_URL");
    // Have Mesos fill in the current user.
    FrameworkInfo framework = FrameworkInfo.newBuilder()
      .setUser(targetUser == null ? "" : targetUser)
      .setName(mesosCloud.getFrameworkName())
      .setRole(mesosCloud.getRole())
      .setPrincipal(mesosCloud.getPrincipal())
      .setCheckpoint(mesosCloud.isCheckpoint())
      .setWebuiUrl(webUrl != null ? webUrl :  "")
      .setFailoverTimeout(DEFAULT_FAILOVER_TIMEOUT)
      .build();

    LOGGER.info("Initializing the Mesos driver with options"
      + "\n" + "Framework Name: " + framework.getName()
      + "\n" + "Principal: " + mesosCloud.getPrincipal()
      + "\n" + "Checkpointing: " + framework.getCheckpoint()
    );

    if (StringUtils.isNotBlank(mesosCloud.getSecret())) {
      Credential credential = Credential.newBuilder()
        .setPrincipal(mesosCloud.getPrincipal())
        .setSecretBytes(ByteString.copyFromUtf8(mesosCloud.getSecret()))
        .build();


      LOGGER.info("Authenticating with Mesos master with principal " + credential.getPrincipal());
      driver = new MesosSchedulerDriver(JenkinsScheduler.this, framework, mesosCloud.getMaster(), credential);
    } else {
      driver = new MesosSchedulerDriver(JenkinsScheduler.this, framework, mesosCloud.getMaster());
    }
    // Start the framework.
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          Status runStatus = driver.run();
          if (runStatus != Status.DRIVER_STOPPED) {
            LOGGER.severe("The Mesos driver was aborted! Status code: " + runStatus.getNumber());
          } else {
            LOGGER.info("The Mesos driver was stopped.");
          }
        } catch(RuntimeException e) {
          LOGGER.log(Level.SEVERE, "Caught a RuntimeException", e);
        } finally {
          SUPERVISOR_LOCK.lock();
          if (driver != null) {
            driver.abort();
          }
          driver = null;
          running = false;
          SUPERVISOR_LOCK.unlock();
        }
      }
    }, "Framework " + mesosCloud.getFrameworkName() + " thread").start();
  }

  public synchronized void stop() {
    SUPERVISOR_LOCK.lock();
    if (driver != null) {
      LOGGER.finer("Stopping Mesos driver.");
      driver.stop();
    } else {
      LOGGER.warning("Unable to stop Mesos driver:  driver is null.");
    }
    running = false;
    SUPERVISOR_LOCK.unlock();
  }

  public synchronized boolean isRunning() {
    return running;
  }

  public synchronized void requestJenkinsSlave(Mesos.SlaveRequest slaveRequest, Mesos.SlaveResult slaveResult) {
    LOGGER.info("Enqueuing jenkins slave request");

    Request request = new Request(slaveRequest, slaveResult);
    if(isResourceLimitReached(request)) {
      LOGGER.info("Maximum number of CPUs or Mem is reached, set request "+ slaveRequest.slave.name +" as failed " +
              "for a later retry." );
      slaveResult.failed(slaveRequest.slave, Mesos.SlaveResult.FAILED_CAUSE.RESOURCE_LIMIT_REACHED);
      return;
    }

    requests.add(request);

    if (driver != null) {
      // Ask mesos to send all offers, even the those we declined earlier.
      // See comment in resourceOffers() for further details.
      driver.reviveOffers();
    }
  }

  private boolean isCpuLimitActivated() {
    // TODO: do activate with boolean value, not with certain value
    return mesosCloud.getMaxCpus() > 0.01;
  }

  private boolean isCpuLimitReached(Request request) {
    double futureUsedCpus = getUsedCpus() + request.request.cpus;
    return isCpuLimitActivated() && mesosCloud.getMaxCpus() < futureUsedCpus;
  }

  private boolean isMemoryLimitActivated() {
    // TODO: do activate with boolean value, not with certain value
    return mesosCloud.getMaxMem() > 0;
  }

  private boolean isMemoryLimitReached(Request request) {
    int futureUsedMemory = getUsedMem() + request.request.mem;
    return isMemoryLimitActivated() && mesosCloud.getMaxMem() < futureUsedMemory;
  }

  private boolean isResourceLimitReached(Request request) {
    return isCpuLimitReached(request) || isMemoryLimitReached(request);
  }

  /**
   * @param slaveName the slave name in jenkins
   * @return the jnlp url for the slave: http://[master]/computer/[slaveName]/slave-agent.jnlp
   */
  private String getJnlpUrl(String slaveName) {
    return joinPaths(joinPaths(joinPaths(jenkinsMaster, "computer"), slaveName), "slave-agent.jnlp");
  }

  /**
   * Slave needs to go through authentication while connecting through jnlp if security is enabled in jenkins.
   * This method gets secret (for jnlp authentication) from jenkins, constructs command line argument and returns it.
   *
   * @param slaveName the slave name in jenkins
   * @return jenkins slave secret corresponding to slave name in the format '-secret <secret>'
   */
  private String getJnlpSecret(String slaveName) {
    String jnlpSecret = "";
    if(Jenkins.getInstance().isUseSecurity()) {
      jnlpSecret = String.format(JNLP_SECRET_FORMAT, jenkins.slaves.JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(slaveName));
    }
    return jnlpSecret;
  }

  private static String joinPaths(String prefix, String suffix) {
    if (prefix.endsWith("/"))   prefix = prefix.substring(0, prefix.length()-1);
    if (suffix.startsWith("/")) suffix = suffix.substring(1, suffix.length());

    return prefix + '/' + suffix;
  }

  public synchronized void terminateJenkinsSlave(String name) {
    LOGGER.info("Terminating jenkins slave " + name);

    TaskID taskId = TaskID.newBuilder().setValue(name).build();

    if (results.containsKey(taskId)) {
      LOGGER.info("Killing mesos task " + taskId);
      driver.killTask(taskId);
    } else {
        // This is handling the situation that a slave was provisioned but it never
        // got scheduled because of resource scarcity and jenkins later tries to remove
        // the offline slave but since it was not scheduled we have to remove it from
        // the request queue. The method has been also synchronized because there is a race
        // between this removal request from jenkins and a resource getting freed up in mesos
        // resulting in scheduling the slave and resulting in orphaned task/slave not monitored
        // by Jenkins.

        for(Request request : requests) {
           if(request.request.slave.name.equals(name)) {
             LOGGER.info("Removing enqueued mesos task " + name);
             requests.remove(request);
             // Also signal the Thread of the MesosComputerLauncher.launch() to exit from latch.await()
             // Otherwise the Thread will stay in WAIT forever -> Leak!
             request.result.failed(request.request.slave, Mesos.SlaveResult.FAILED_CAUSE.SLAVE_NEVER_SCHEDULED);
             return;
           }
        }

        LOGGER.warning("Asked to kill unknown mesos task " + taskId);
    }

    // Since this task is now running, we should not start this task up again at a later point in time
    finishedTasks.add(taskId);

    if (mesosCloud.isOnDemandRegistration()) {
      supervise();
    }

  }

  @Override
  public void registered(SchedulerDriver driver, FrameworkID frameworkId, MasterInfo masterInfo) {
    LOGGER.info("Framework registered! ID = " + frameworkId.getValue());
  }

  @Override
  public void reregistered(SchedulerDriver driver, MasterInfo masterInfo) {
    LOGGER.info("Framework re-registered");
  }

  @Override
  public void disconnected(SchedulerDriver driver) {
    LOGGER.info("Framework disconnected!");
  }

  @Override
  public synchronized void resourceOffers(SchedulerDriver driver, List<Offer> offers) {
    LOGGER.fine("Received offers " + offers.size());

    for (Offer offer : offers) {
      if (requests.isEmpty()) {
        // Decline offer for a longer period if no slave is waiting to get spawned.
        // This prevents unnecessarily getting offers every few seconds and causing
        // starvation when running a lot of frameworks.
        double rejectOfferDuration = DEFAULT_DECLINE_OFFER_DURATION;
        LOGGER.info("No slave in queue. Rejecting offers for '" + mesosCloud.getFrameworkName() + "' for " + rejectOfferDuration + " s");
        Filters filters = Filters.newBuilder().setRefuseSeconds(rejectOfferDuration).build();
        driver.declineOffer(offer.getId(), filters);
        continue;
      }

      // get matching offer for request, and create task
      TaskInfo task = null;
      if(isOfferAvailable(offer)) {
        for (Request request : requests) {
          if (matches(offer, request)) {
            LOGGER.fine("Offer matched! Creating mesos task");

            try {
              task = createMesosTask(offer, request);

              // launch task for request
              if (task != null) {
                launchMesosTask(offer, task, request);
              }
            } catch (Exception e) {
              LOGGER.log(Level.SEVERE, e.getMessage(), e);
            }
            requests.remove(request);
            break;
          }
        }
      }

      // refuse/decline offer if no task was created
      if (task == null) {
        refuseOffer(offer);
      }
    }
  }

  private void launchMesosTask(Offer offer, TaskInfo task, Request request) {
    TaskID taskId = task.getTaskId();
    LOGGER.fine("Launching task " + taskId.getValue() + " with URI " +
            joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX));

    List<TaskInfo> tasks = new LinkedList<TaskInfo>();
    tasks.add(task);

    List<OfferID> offerIDs = new LinkedList<OfferID>();
    offerIDs.add(offer.getId());

    Filters filters = Filters.newBuilder().setRefuseSeconds(1).build();
    driver.launchTasks(offerIDs, tasks, filters);

    List<DockerInfo.PortMapping> actualPortMappings = task.getContainer().getDocker().getPortMappingsList();

    Mesos.JenkinsSlave jenkinsSlave = new Mesos.JenkinsSlave(
            request.request.slave.getName(),
            offer.getHostname(),
            actualPortMappings,
            request.request.slaveInfo.getLabelString(),
            request.request.slave.getNumExecutors(),
            request.request.slave.linkedItem,
            request.request.cpus,
            request.request.mem);

    // "transition" to finished
    results.put(taskId, new Result(request.result, jenkinsSlave));
    finishedTasks.add(taskId);
  }

  private boolean isOfferAvailable(Offer offer) {
    if(offer.hasUnavailability()) {
      Unavailability unavailability = offer.getUnavailability();

      Date startTime = new Date(TimeUnit.NANOSECONDS.toMillis(unavailability.getStart().getNanoseconds()));
      long duration = unavailability.getDuration().getNanoseconds();
      Date endTime = new Date(startTime.getTime() + TimeUnit.NANOSECONDS.toMillis(duration));
      Date currentTime = new Date();

      return !(startTime.before(currentTime) && endTime.after(currentTime));
    }

    return true;
  }

  private boolean matches(Offer offer, Request request) {
    double cpus = 0;
    double mem = 0;
    List<Range> ports = new ArrayList<Range>();

    for (Resource resource : offer.getResourcesList()) {
      String resourceRole = resource.getRole();

      // Start: Not needed, b/c FW only gets resources of * and framework.setRole()
      String expectedRole = mesosCloud.getRole();
      if (! (resourceRole.equals(expectedRole) || resourceRole.equals("*"))) {
        LOGGER.warning("Resource role " + resourceRole +
            " doesn't match expected role " + expectedRole);
        continue;
      }
      // End: Not needed, b/c FW only gets resources of * and framework.setRole()

      // Add resources of * and role
      if (resource.getName().equals("cpus")) {
        if (resource.getType().equals(Value.Type.SCALAR)) {
          cpus += resource.getScalar().getValue();
        } else {
          LOGGER.severe("Cpus resource was not a scalar: " + resource.getType().toString());
        }
      } else if (resource.getName().equals("mem")) {
        if (resource.getType().equals(Value.Type.SCALAR)) {
          mem += resource.getScalar().getValue();
        } else {
          LOGGER.severe("Mem resource was not a scalar: " + resource.getType().toString());
        }
      } else if (resource.getName().equals("disk")) {
        LOGGER.fine("Ignoring disk resources from offer");
      } else if (resource.getName().equals("ports")) {
        if (resource.getType().equals(Value.Type.RANGES)) {
          ports.addAll(resource.getRanges().getRangeList());
        } else {
          LOGGER.severe("Ports resource was not a range: " + resource.getType().toString());
        }
      } else {
        LOGGER.warning("Ignoring unknown resource type: " + resource.getName());
      }
    }

    MesosSlaveInfo.ContainerInfo containerInfo = request.request.slaveInfo.getContainerInfo();

    boolean hasPortMappings = containerInfo != null && containerInfo.hasPortMappings();

    boolean hasPortResources = !ports.isEmpty();

    if (hasPortMappings && !hasPortResources) {
      LOGGER.severe("No ports resource present");
    }

    // Check for sufficient cpu and memory resources in the offer.
    double requestedCpus = request.request.cpus;
    double requestedMem = request.request.mem;
    // Get matching slave attribute for this label.
    JSONObject slaveAttributes = getMesosCloud().getSlaveAttributeForLabel(request.request.slaveInfo.getLabelString());

    if (requestedCpus <= cpus
            && requestedMem <= mem
            && !(hasPortMappings && !hasPortResources)
            && slaveAttributesMatch(offer, slaveAttributes)) {
      return true;
    } else {
      String requestedPorts = containerInfo != null
              ? StringUtils.join(containerInfo.getPortMappings().toArray(), "/")
              : "";

      LOGGER.fine(
          "Offer not sufficient for slave request:\n" +
          offer.getResourcesList().toString() +
          "\n" + offer.getAttributesList().toString() +
          "\nRequested for Jenkins slave:\n" +
          "  cpus:  " + requestedCpus + "\n" +
          "  mem:   " + requestedMem + "\n" +
          "  ports: " + requestedPorts + "\n" +
          "  attributes:  " + (slaveAttributes == null ? ""  : slaveAttributes.toString()));
      return false;
    }
  }

  /**
  * Checks whether the cloud Mesos slave attributes match those from the Mesos offer.
  *
  * @param offer Mesos offer data object.
  * @return true if all the offer attributes match and false if not.
  */
  private boolean slaveAttributesMatch(Offer offer, JSONObject slaveAttributes) {

    //Accept any and all Mesos slave offers by default.
    boolean slaveTypeMatch = true;

    //Collect the list of attributes from the offer as key-value pairs
    Map<String, String> attributesMap = new HashMap<String, String>();
    for (Attribute attribute : offer.getAttributesList()) {
      attributesMap.put(attribute.getName(), attribute.getText().getValue());
    }

    if (slaveAttributes != null && slaveAttributes.size() > 0) {

      //Iterate over the cloud attributes to see if they exist in the offer attributes list.
      Iterator iterator = slaveAttributes.keys();
      while (iterator.hasNext()) {

        String key = (String) iterator.next();

        //If there is a single absent attribute then we should reject this offer.
        if (!(attributesMap.containsKey(key) && attributesMap.get(key).toString().equals(slaveAttributes.getString(key)))) {
          slaveTypeMatch = false;
          break;
        }
      }
    }

    return slaveTypeMatch;
  }

  @VisibleForTesting
  void setDriver(SchedulerDriver driver) {
    this.driver = driver;
  }

  @VisibleForTesting
  SortedSet<Long> findPortsToUse(Offer offer, int maxCount) {
      SortedSet<Long> portsToUse = new TreeSet<Long>();
      List<Value.Range> portRangesList = null;

      // Locate the port resource in the offer
      for (Resource resource : offer.getResourcesList()) {
        if (resource.getName().equals(PORT_RESOURCE_NAME)) {
          portRangesList = resource.getRanges().getRangeList();
          break;
        }
      }

      LOGGER.fine("portRangesList=" + portRangesList);

      /**
       * We need to find maxCount ports to use.
       * We are provided a list of port ranges to use
       * We are assured by the offer check that we have enough ports to use
       */
      // Check this port range for ports that we can use
      for (Value.Range currentPortRange : portRangesList) {
        // Check each port until we reach the end of the current range
        long begin = currentPortRange.getBegin();
        long end = currentPortRange.getEnd();
        for (long candidatePort = begin; candidatePort <= end && portsToUse.size() < maxCount; candidatePort++) {
            portsToUse.add(candidatePort);
        }
      }

      return portsToUse;
  }

  private TaskInfo createMesosTask(Offer offer, Request request) {
    final String slaveName = request.request.slave.name;
    TaskID taskId = TaskID.newBuilder().setValue(slaveName).build();

    LOGGER.fine("Creating task " + taskId.getValue() + " with URI " +
                joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX));

    // task for request already exists, or jenkins agent already created for it
    if (isExistingTask(taskId)) {
        LOGGER.finer("Task '" + taskId + "' already exists");
        return null;
    }

    for (final Computer computer : Jenkins.getInstance().getComputers()) {
        if (!MesosComputer.class.isInstance(computer)) {
            LOGGER.finer("Not a mesos computer, skipping");
            continue;
        }

        MesosComputer mesosComputer = (MesosComputer) computer;

        if (mesosComputer == null) {
            LOGGER.fine("The mesos computer is null, skipping");
            continue;
        }

        MesosSlave mesosSlave = mesosComputer.getNode();

        if (taskId.getValue().equals(computer.getName()) && mesosSlave.isPendingDelete()) {
            LOGGER.fine("This mesos task " + taskId.getValue() + " is pending deletion. Not launching another task");
            return null;
        }
    }

    CommandInfo.Builder commandBuilder = getCommandInfoBuilder(request);
    TaskInfo.Builder taskBuilder = getTaskInfoBuilder(offer, request, taskId, commandBuilder);

    if (request.request.slaveInfo.getContainerInfo() != null) {
        getContainerInfoBuilder(offer, request, slaveName, taskBuilder);
    }


    return taskBuilder.build();

  }

  private void detectAndAddAdditionalURIs(Request request, CommandInfo.Builder commandBuilder) {

    if (request.request.slaveInfo.getAdditionalURIs() != null) {
      for (MesosSlaveInfo.URI uri : request.request.slaveInfo.getAdditionalURIs()) {
        commandBuilder.addUris(
            CommandInfo.URI.newBuilder().setValue(
                uri.getValue()).setExecutable(uri.isExecutable()).setExtract(uri.isExtract()));
      }
    }
  }

  private TaskInfo.Builder getTaskInfoBuilder(Offer offer, Request request, TaskID taskId, CommandInfo.Builder commandBuilder) {
    TaskInfo.Builder builder = TaskInfo.newBuilder()
        .setName("task " + taskId.getValue())
        .setTaskId(taskId)
        .setSlaveId(offer.getSlaveId())
        .setCommand(commandBuilder.build());

    double cpusNeeded = request.request.cpus;
    double memNeeded = request.request.mem;

    for (Resource r : offer.getResourcesList()) {
      if (r.getName().equals("cpus") && cpusNeeded > 0) {
        double cpus = Math.min(r.getScalar().getValue(), cpusNeeded);
        builder.addResources(
            Resource
                .newBuilder()
                .setName("cpus")
                .setType(Value.Type.SCALAR)
                .setRole(r.getRole())
                .setScalar(
                    Value.Scalar.newBuilder()
                        .setValue(cpus).build()).build());
        cpusNeeded -= cpus;
      } else if (r.getName().equals("mem") && memNeeded > 0) {
        double mem = Math.min(r.getScalar().getValue(), memNeeded);
        builder.addResources(
            Resource
                .newBuilder()
                .setName("mem")
                .setType(Value.Type.SCALAR)
                .setRole(r.getRole())
                .setScalar(
                    Value.Scalar
                        .newBuilder()
                        .setValue(mem)
                        .build()).build());
        memNeeded -= mem;
      } else if (cpusNeeded == 0 && memNeeded == 0) {
        break;
      }
    }
    return builder;
  }

  private String replaceTokens(String text, Request request) {
      String result = StringUtils.replace(text, ITEM_FULLNAME_TOKEN, request.request.slave.linkedItem);
      result = StringUtils.replace(result, FRAMEWORK_NAME_TOKEN, mesosCloud.getFrameworkName());
      result = StringUtils.replace(result, JENKINS_MASTER_TOKEN, jenkinsMaster);

      return result;
  }

  private void getContainerInfoBuilder(Offer offer, Request request, String slaveName, TaskInfo.Builder taskBuilder) {
      MesosSlaveInfo.ContainerInfo containerInfo = request.request.slaveInfo.getContainerInfo();
      ContainerInfo.Type containerType = ContainerInfo.Type.valueOf(containerInfo.getType());

      ContainerInfo.Builder containerInfoBuilder = ContainerInfo.newBuilder() //
              .setType(containerType); //

      switch(containerType) {
        case DOCKER:
          LOGGER.info("Launching in Docker Mode:" + containerInfo.getDockerImage());
          DockerInfo.Builder dockerInfoBuilder = DockerInfo.newBuilder() //
              .setImage(containerInfo.getDockerImage())
              .setPrivileged(containerInfo.getDockerPrivilegedMode() != null ? containerInfo.getDockerPrivilegedMode() : false)
              .setForcePullImage(containerInfo.getDockerForcePullImage() != null ? containerInfo.getDockerForcePullImage() : false);

          if (containerInfo.getParameters() != null) {
            for (MesosSlaveInfo.Parameter parameter : containerInfo.getParameters()) {
              LOGGER.info("Adding Docker parameter '" + parameter.getKey() + ":" + parameter.getValue() + "'");
              dockerInfoBuilder.addParameters(Parameter.newBuilder().setKey(parameter.getKey()).setValue(parameter.getValue()).build());
            }
          }

          String networking = request.request.slaveInfo.getContainerInfo().getNetworking();
          Network dockerNetwork = Network.valueOf(networking);

          dockerInfoBuilder.setNetwork(dockerNetwork);

          //  https://github.com/jenkinsci/mesos-plugin/issues/109
          if (!Network.HOST.equals(dockerNetwork)) {
            containerInfoBuilder.setHostname(slaveName);
          }

          if (Network.USER.equals(dockerNetwork)) {
            /*
             * create network name out of principal and framework name to be relatively secure
             * because, the password of the principal should only be known to the admins who configure the network
             * thus, other containers of other frameworks cannot use this network in theory
             *
             * TODO: let choose between auto-generated and configured
             */
            final String networkName = String.format("%s-%s", //
                    mesosCloud.getPrincipal(), //
                    StringUtils.replace(mesosCloud.getFrameworkName(), " ", "-"));

            LOGGER.log(Level.FINER, "Setting the USER network name to '" + networkName + "'");

            NetworkInfo.Builder networkInfoBuilder = NetworkInfo.newBuilder().setName(networkName);
            containerInfoBuilder.addNetworkInfos(networkInfoBuilder);
          }

          if (request.request.slaveInfo.getContainerInfo().hasPortMappings()) {
              Set<MesosSlaveInfo.PortMapping> portMappings = request.request.slaveInfo.getContainerInfo().getPortMappings();
              Set<Long> portsToUse = findPortsToUse(offer, portMappings.size());
              Iterator<Long> iterator = portsToUse.iterator();
              Value.Ranges.Builder portRangesBuilder = Value.Ranges.newBuilder();

              for (MesosSlaveInfo.PortMapping portMapping : portMappings) {
                  PortMapping.Builder portMappingBuilder = PortMapping.newBuilder() //
                          .setContainerPort(portMapping.getContainerPort()) //
                          .setProtocol(portMapping.getProtocol());

                  Long portToUse = portMapping.getHostPort() == null ? iterator.next() : portMapping.getHostPort();

                  portMappingBuilder.setHostPort(portToUse.intValue());

                  portRangesBuilder.addRange(
                    Value.Range
                      .newBuilder()
                      .setBegin(portToUse)
                      .setEnd(portToUse)
                  );

                  LOGGER.finest("Adding portMapping: " + portMapping);
                  dockerInfoBuilder.addPortMappings(portMappingBuilder);
              }

              taskBuilder.addResources(
                Resource
                  .newBuilder()
                  .setName("ports")
                  .setType(Value.Type.RANGES)
                  .setRanges(portRangesBuilder)
                  );
          } else {
              LOGGER.fine("No portMappings found");
          }

          containerInfoBuilder.setDocker(dockerInfoBuilder);
          break;
        default:
          LOGGER.warning("Unknown container type:" + containerInfo.getType());
      }

      if (containerInfo.getVolumes() != null) {
        for (MesosSlaveInfo.Volume volume : containerInfo.getVolumes()) {
          LOGGER.info("Adding volume '" + volume.getContainerPath() + "'");
          Volume.Builder volumeBuilder = Volume.newBuilder()
              .setContainerPath(replaceTokens(volume.getContainerPath(), request))
              .setMode(volume.isReadOnly() ? Mode.RO : Mode.RW);

          String hostPath = volume.getHostPath();
          if (!StringUtils.isBlank(hostPath)) {
            volumeBuilder.setHostPath(replaceTokens(hostPath, request));
          }

          containerInfoBuilder.addVolumes(volumeBuilder.build());
        }
      }

      taskBuilder.setContainer(containerInfoBuilder.build());
    }

  @VisibleForTesting
  CommandInfo.Builder getCommandInfoBuilder(Request request) {
        CommandInfo.Builder commandBuilder = getBaseCommandBuilder(request);
        detectAndAddAdditionalURIs(request, commandBuilder);
        return commandBuilder;
  }

  String generateJenkinsCommand2Run(Request request) {

    int jvmMem = request.request.slaveInfo.getSlaveMem();
    String jvmArgString = request.request.slaveInfo.getJvmArgs();
    String jnlpArgString = request.request.slaveInfo.getJnlpArgs();
    String slaveName = request.request.slave.name;
    MesosSlaveInfo.RunAsUserInfo runAsUserInfo = request.request.slaveInfo.getRunAsUserInfo();
    List<MesosSlaveInfo.Command> additionalCommands = request.request.slaveInfo.getAdditionalCommands();

    String slaveCmd = String.format(SLAVE_COMMAND_FORMAT,
            jvmMem,
            jvmArgString,
            jnlpArgString,
            getJnlpSecret(slaveName),
            getJnlpUrl(slaveName));

    if (runAsUserInfo != null) {
      slaveCmd = runAsUserInfo.getCommand()
              .replace(MesosSlaveInfo.RunAsUserInfo.TOKEN_USERNAME, runAsUserInfo.getUsername())
              .replace(MesosSlaveInfo.RunAsUserInfo.TOKEN_SLAVE_COMMAND, slaveCmd);
    }

    StringBuilder commandStringBuilder = new StringBuilder();

    if (additionalCommands != null && !additionalCommands.isEmpty()) {
      for (MesosSlaveInfo.Command additionalCommand : additionalCommands) {
        commandStringBuilder.append(replaceTokens(additionalCommand.getValue(), request) + " && ");
      }
      commandStringBuilder.append("exec ");
      commandStringBuilder.append(slaveCmd);
      return commandStringBuilder.toString();
    }

    return slaveCmd;
  }

    private CommandInfo.Builder getBaseCommandBuilder(Request request) {

        CommandInfo.Builder commandBuilder = CommandInfo.newBuilder();

        String command = StringUtils.EMPTY;

        // make an "api call" to mesos so that Jenkins knows that he has to create a new slave on Jenkins instance.
        // user network (in our case) means an isolated network, so the fetcher will not be able to access jenkins
        String slaveRequestUri = joinPaths(jenkinsMaster, String.format(SLAVE_REQUEST_FORMAT, request.request.slave.getName()));

        String slaveJarUri = joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX);

        Network slaveNetwork = Network.NONE;
        if (request.request.slaveInfo.getContainerInfo() != null) {
            slaveNetwork = Network.valueOf(request.request.slaveInfo.getContainerInfo().getNetworking());
        }

        if (Network.USER.equals(slaveNetwork)) {
            String requestSlaveCommand = "curl -o ${MESOS_SANDBOX}/" + request.request.slave.getName() + " " + slaveRequestUri;
            String downloadSlaveJarCommand = "curl -o ${MESOS_SANDBOX}/slave.jar " + slaveJarUri;

            command = requestSlaveCommand + " && " + downloadSlaveJarCommand + " && ";
        } else {
            commandBuilder.addUris(
                    CommandInfo.URI.newBuilder().setValue(slaveRequestUri).setExecutable(false).setExtract(false));
            commandBuilder.addUris(
                    CommandInfo.URI.newBuilder().setValue(slaveJarUri).setExecutable(false).setExtract(false));
        }

        command += generateJenkinsCommand2Run(request);

        if (request.request.slaveInfo.getContainerInfo() != null &&
                request.request.slaveInfo.getContainerInfo().getUseCustomDockerCommandShell()) {
            // Ref http://mesos.apache.org/documentation/latest/upgrades
            // regarding setting the shell value, and the impact on the command to be
            // launched
            String customShell = request.request.slaveInfo.getContainerInfo().getCustomDockerCommandShell();
            if (StringUtils.stripToNull(customShell) == null) {
                throw new IllegalArgumentException("Invalid custom shell argument supplied");
            }

            LOGGER.info(String.format("About to use custom shell: %s ", customShell));
            commandBuilder.setShell(false);
            commandBuilder.setValue(customShell);
            List args = new ArrayList();
            args.add(command);
            commandBuilder.addAllArguments(args);

        } else {
            LOGGER.info("About to use default shell ....");
            commandBuilder.setValue(command);
        }

        return commandBuilder;
    }

  /**
   * Checks if the given taskId already exists or just finished running. If it has, then refuse the offer.
   * @param taskId The task id
   * @return True if the task already exists, false otherwise
   */
  @VisibleForTesting
  boolean isExistingTask(TaskID taskId) {
      // If the task has already been queued, don't launch it again
      if (results.containsKey(taskId)) {
          LOGGER.info("Task " + taskId.getValue() + " has already been launched, ignoring and refusing offer");
          return true;
      }

      // If the task has already finished, then do not start it up again even if we are offered it
      if (finishedTasks.contains(taskId)) {
          LOGGER.info("Task " + taskId.getValue() + " has already finished. Ignoring and refusing offer");
          return true;
      }

      return false;
  }

  /**
   * Refuses the offer provided by launching no tasks.
   * @param offer The offer to refuse
   */
  @VisibleForTesting
  void refuseOffer(Offer offer) {
      driver.declineOffer(offer.getId());
  }

  @Override
  public void offerRescinded(SchedulerDriver driver, OfferID offerId) {
    LOGGER.info("Rescinded offer " + offerId);
  }

  public void reconcileTask(String taskID) {
    TaskID id = TaskID.newBuilder().setValue(taskID).build();
    TaskStatus taskStatus = TaskStatus.newBuilder().setTaskId(id).setState(TaskState.TASK_LOST).build();
    TaskStatus[] t = { taskStatus };
    driver.reconcileTasks(Arrays.asList(t));
  }

  public void reconcileAllTasks() {
    for(TaskID task : results.keySet()) {
      reconcileTask(task.getValue());
    }
  }

  @Override
  public void statusUpdate(SchedulerDriver driver, TaskStatus status) {
    TaskID taskId = status.getTaskId();
    LOGGER.fine("Status update: task " + taskId + " is in state " + status.getState() +
                (status.hasMessage() ? " with message '" + status.getMessage() + "'" : ""));

    if (!results.containsKey(taskId)) {
      // The task might not be present in the 'results' map if this is a duplicate terminal
      // update.
      LOGGER.fine("Ignoring status update " + status.getState() + " for unknown task " + taskId);
      return;
    }

    //setData
    Node node = Jenkins.getInstance().getNode(taskId.getValue());
    MesosSlave mesosSlave = null;

    if(node != null) {
      mesosSlave = (MesosSlave) node;
      mesosSlave.setTaskStatus(status);
    }

    Result result = results.get(taskId);
    boolean terminalState = false;


    switch (status.getState()) {
    case TASK_STAGING:
    case TASK_STARTING:
      break;
    case TASK_RUNNING:
      result.result.running(result.slave);
      if(mesosSlave != null && StringUtils.isBlank(mesosSlave.getDockerContainerID())) {
        mesosSlave.setDockerContainerID(extractContainerIdFromTaskStatus(status));
      }
      break;
    case TASK_FINISHED:
      result.result.finished(result.slave);
    case TASK_KILLED:
      terminalState = true;
      break;
    case TASK_FAILED:
      result.result.failed(result.slave, Mesos.SlaveResult.FAILED_CAUSE.MESOS_CLOUD_REPORTED_TASK_FAILED);
      terminalState = true;
      break;
    case TASK_ERROR:
      result.result.failed(result.slave, Mesos.SlaveResult.FAILED_CAUSE.MESOS_CLOUD_REPORTED_TASK_ERROR);
      terminalState = true;
      break;
    case TASK_LOST:
      result.result.failed(result.slave, Mesos.SlaveResult.FAILED_CAUSE.MESOS_CLOUD_REPORTED_TASK_LOST);
      terminalState = true;
      break;
    default:
      throw new IllegalStateException("Invalid State: " + status.getState());
    }

    if (terminalState) {
      results.remove(taskId);
    }

    if (mesosCloud.isOnDemandRegistration()) {
      supervise();
    }
  }

  @Override
  public void frameworkMessage(SchedulerDriver driver, ExecutorID executorId,
      SlaveID slaveId, byte[] data) {
    LOGGER.info("Received framework message from executor " + executorId
        + " of slave " + slaveId);
  }

  @Override
  public void slaveLost(SchedulerDriver driver, SlaveID slaveId) {
    LOGGER.info("Slave " + slaveId + " lost!");
  }

  @Override
  public void executorLost(SchedulerDriver driver, ExecutorID executorId,
      SlaveID slaveId, int status) {
    LOGGER.info("Executor " + executorId + " of slave " + slaveId + " lost!");
  }

  @Override
  public void error(SchedulerDriver driver, String message) {
    LOGGER.severe(message);
    if (message.contains("Framework has been removed")) {
      LOGGER.info("Framework was removed from MesosCloud, so we need to restart Mesos");
      //force Mesos restart
      mesosCloud.restartMesos(true);
    }
  }

  /**
  * @return the mesosCloud
  */
  private MesosCloud getMesosCloud() {
    return mesosCloud;
  }

  /**
  * @param mesosCloud the mesosCloud to set
  */
  protected void setMesosCloud(MesosCloud mesosCloud) {
    this.mesosCloud = mesosCloud;
  }

  public List<Request> getRequestsMatchingLabel(Label label) {
    List<Request> foundRequests = new ArrayList<Request>();
    try {
      for (Request request : requests) {
        if (request.request.slaveInfo.getLabelString().equals(label.getDisplayName())) {
          foundRequests.add(request);
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error while trying to find a request matching with label '" + label + "'", e);
    }

    return foundRequests;
  }

  public class Result {
    private final Mesos.SlaveResult result;
    private final Mesos.JenkinsSlave slave;

    private Result(Mesos.SlaveResult result, Mesos.JenkinsSlave slave) {
      this.result = result;
      this.slave = slave;
    }

    public Mesos.SlaveResult getResult() {
        return result;
    }

    public Mesos.JenkinsSlave getSlave() {
        return slave;
    }
  }

  @VisibleForTesting
  public class Request {
    private final Mesos.SlaveRequest request;
    private final Mesos.SlaveResult result;

    public Request(Mesos.SlaveRequest request, Mesos.SlaveResult result) {
      this.request = request;
      this.result = result;
    }
  }

  public int getNumberOfPendingTasks() {
    return requests.size();
  }

  public int getNumberOfActiveTasks() {
    return results.size();
  }

  public void clearResults() {
    results.clear();
  }

  /**
   * Disconnect framework, if we don't have active mesos slaves. Also, make
   * sure JenkinsScheduler's request queue is empty.
   */
  public static void supervise() {
	SUPERVISOR_LOCK.lock();
    Collection<Mesos> clouds = Mesos.getAllClouds();
    try {
      for (Mesos cloud : clouds) {
        try {
          JenkinsScheduler scheduler = (JenkinsScheduler) cloud.getScheduler();
          if (scheduler != null) {
            boolean pendingTasks = (scheduler.getNumberOfPendingTasks() > 0);
            boolean activeSlaves = false;
            boolean activeTasks = (scheduler.getNumberOfActiveTasks() > 0);
            List<Node> slaveNodes = Jenkins.getInstance().getNodes();
            for (Node node : slaveNodes) {
              if (node instanceof MesosSlave) {
                activeSlaves = true;
                break;
              }
            }
            // If there are no active slaves, we should clear up results.
            if (!activeSlaves) {
              scheduler.clearResults();
              activeTasks = false;
            }
            LOGGER.info("Active slaves: " + activeSlaves
                + " | Pending tasks: " + pendingTasks + " | Active tasks: " + activeTasks);
            if (!activeTasks && !activeSlaves && !pendingTasks) {
              LOGGER.info("No active tasks, or slaves or pending slave requests. Stopping the scheduler.");
              cloud.stopScheduler();
            }
          } else {
            LOGGER.info("Scheduler already stopped. NOOP.");
          }
        } catch (Exception e) {
          LOGGER.info("Exception: " + e);
        }
      }
    } finally {
      SUPERVISOR_LOCK.unlock();
    }
  }

  public Request getRequestForLinkedItem(String linkedItem) {
    try {
      for (Request request : requests) {
        if (StringUtils.equals(request.request.slave.getLinkedItem(),linkedItem)) {
          return request;
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error while finding request: ", e);
    }
    return null;
  }

  public boolean removeRequestForLinkedItem(String linkedItem) {
    Request request = getRequestForLinkedItem(linkedItem);
    if(request != null) {
      requests.remove(request);
      return true;
    }
    return false;
  }

  public Result getResult(String slaveName) {
    TaskID taskId = TaskID.newBuilder().setValue(slaveName).build();
    Result result = results.get(taskId);

    return result;
  }

  public double getUsedCpus() {
    double cpus = 0.0;
    for(Request request: requests) {
      cpus += request.request.cpus;
    }

    for(Result result: results.values()) {
      cpus += result.getSlave().getCpus();
    }
    return cpus;
  }

  public int getUsedMem() {
    int mem = 0;
    for(Request request:requests) {
      mem += request.request.mem;
    }

    for(Result result: results.values()) {
      mem += result.getSlave().getMem();
    }
    return mem;
  }

  public String getJenkinsMaster() {
    return jenkinsMaster;
  }

  public void setJenkinsMaster(String jenkinsMaster) {
    this.jenkinsMaster = jenkinsMaster;
  }

  private String extractContainerIdFromTaskStatus(TaskStatus taskStatus) {
    try {
      if (taskStatus != null) {
        String taskStatusData = taskStatus.getData().toStringUtf8();
        if(!taskStatusData.isEmpty()) {
          String jsonStr = taskStatusData.replaceFirst("\\[", "").substring(0, (taskStatusData.lastIndexOf(']') - 1)).trim();
          JSONObject jsonObject = JSONObject.fromObject(jsonStr);
          return jsonObject.getString("Name").replaceFirst("/", "").trim();
        } else {
          LOGGER.log(Level.WARNING, "Unable to get DockerContainerId, taskStatus has no data");
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Failed to get DockerContainerID from TaskStatus", e);
    }
    return null;
  }
}
