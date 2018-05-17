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
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.*;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.scheduling.*;
import org.jenkinsci.plugins.mesos.scheduling.Request;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class JenkinsScheduler implements Scheduler {

  protected static final Logger LOGGER = Logger.getLogger(JenkinsScheduler.class.getName());

  public static final Lock SUPERVISOR_LOCK = new ReentrantLock();

  private static final double DEFAULT_NO_REQUESTS_DECLINE_OFFER_DURATION = TimeUnit.MINUTES.toSeconds(10);
  private static final double DEFAULT_FAILOVER_TIMEOUT = TimeUnit.DAYS.toSeconds(7);

  /** pending tasks/requests */
  private BlockingQueue<Request> requests;
  /** active tasks */
  private Map<TaskID, Result> results;
  /** finished tasks */
  private Set<TaskID> finishedTasks;

  private String jenkinsMaster;
  private final String displayName;

  private volatile SchedulerDriver driver;
  private volatile MesosCloud mesosCloud;
  private volatile boolean running;


  public JenkinsScheduler(String jenkinsMaster, MesosCloud mesosCloud, String displayName) {
    LOGGER.info("Creating scheduler '" + displayName
            + " (Jenkins master: " + jenkinsMaster + ","
            + "Mesos master: " + mesosCloud.getMaster() + ")");

    this.jenkinsMaster = jenkinsMaster;
    this.mesosCloud = mesosCloud;
    this.displayName = displayName;

    this.requests = new LinkedBlockingQueue<>();
    this.results = new HashMap<>();
    this.finishedTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
  }

  public static JenkinsScheduler createScheduler(String jenkinsMaster, MesosCloud mesosCloud) {
    String schedulerName = mesosCloud.getSchedulerName();

    if (StringUtils.equals(schedulerName, JenkinsSchedulerOld.NAME)) {
      return new JenkinsSchedulerOld(jenkinsMaster, mesosCloud);
    } else {
      return new JenkinsSchedulerNew(jenkinsMaster, mesosCloud);
    }
  }

  public synchronized void init() {
    // This is to ensure that isRunning() returns true even when the driver is not yet inside run().
    // This is important because MesosCloud.provision() starts a new framework whenever isRunning() is false.
    running = true;
    String targetUser = mesosCloud.getSlavesUser();
    String webUrl = Jenkins.get().getRootUrl();
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
    new Thread(() -> {
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
    }, "Framework " + mesosCloud.getFrameworkName() + " thread").start();
  }

  @SuppressWarnings("DeprecatedIsStillUsed")
  @Deprecated
  private void debugLogOffers(List<Offer> offers) {
    // DEBUG INFO start
    LOGGER.info("=====[ Offers for " + mesosCloud.getFrameworkName() + " ]=====");
    for (Offer offer: offers) {
      LOGGER.info("-----(Resources for Offer: " + offer.getId().getValue() + "( " + offer.getHostname() + ") )-----");
      for (Resource resource: offer.getResourcesList()) {
        final String value;
        switch (resource.getType()) {
          case SCALAR:
            value = Double.toString(resource.getScalar().getValue());
            break;
          case RANGES:
            value = resource.getRanges().getRangeList().toString();
            break;
          case SET:
            value = resource.getSet().getItemList().toString();
            break;
          default:
            value = "unknown";
        }

        LOGGER.info("Name: " + resource.getName() + "; Role: " + resource.getRole() + "; Type: " + resource.getType() + "; Value: " + value);
      }
      LOGGER.info("------------------------------");
    }
    LOGGER.info("==============================");
    // DEBUG info end
  }


  /**
   * Disconnect framework, if we don't have active mesos slaves. Also, make
   * sure JenkinsScheduler's request queue is empty.
   */
  public static void supervise() {
    SUPERVISOR_LOCK.lock();
    try {
      Collection<Mesos> clouds = Mesos.getAllClouds();
      for (Mesos cloud : clouds) {
        try {
          JenkinsScheduler scheduler = (JenkinsScheduler) cloud.getScheduler();
          if (scheduler != null) {
            boolean pendingTasks = (scheduler.getNumberOfPendingTasks() > 0);
            boolean activeSlaves = false;
            boolean activeTasks = (scheduler.getNumberOfActiveTasks() > 0);
            List<Node> slaveNodes = Jenkins.get().getNodes();
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

  public synchronized void stop() {
    SUPERVISOR_LOCK.lock();
    try {
      if (driver != null) {
        LOGGER.finer("Stopping Mesos driver");
        driver.stop();
      } else {
        LOGGER.warning("Unable to stop Mesos driver: driver is null");
      }
    } finally {
      running = false;
      SUPERVISOR_LOCK.unlock();
    }
  }

  public synchronized boolean isRunning() {
    return running;
  }

  public synchronized void requestJenkinsSlave(SlaveRequest slaveRequest, SlaveResult slaveResult) {
    LOGGER.info("Enqueuing jenkins slave request");

    Request request = new Request(slaveRequest, slaveResult);
    if(isResourceLimitReached(request)) {
      LOGGER.info("Maximum number of CPUs or Mem is reached, set request "+ slaveRequest.getSlave().getName() +" as failed " +
              "for a later retry." );

      JenkinsSlave.ResultJenkinsSlave resultJenkinsSlave =
              new JenkinsSlave.ResultJenkinsSlave(request.getRequest().getSlave());
      slaveResult.failed(resultJenkinsSlave, SlaveResult.FAILED_CAUSE.RESOURCE_LIMIT_REACHED);
      return;
    }

    enqueueRequest(request);

    if (driver != null) {
      // Ask mesos to send all offers, even the those we declined earlier.
      // See comment in resourceOffers() for further details.
      // TODO: wait for lock to release (when declining offers), dont do this when not needed (no declined offers)?
      driver.reviveOffers();
    }
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

      for (Request request : requests) {
        String requestedSlaveName = request.getRequest().getSlave().getName();
        if(StringUtils.equals(requestedSlaveName, name)) {
          LOGGER.info("Removing enqueued mesos task " + name);
          requests.remove(request);
          // Also signal the Thread of the MesosComputerLauncher.launch() to exit from latch.await()
          // Otherwise the Thread will stay in WAIT forever -> Leak!
          JenkinsSlave.ResultJenkinsSlave resultJenkinsSlave =
                  new JenkinsSlave.ResultJenkinsSlave(request.getRequest().getSlave());
          request.getResult().failed(resultJenkinsSlave, SlaveResult.FAILED_CAUSE.SLAVE_NEVER_SCHEDULED);
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

  /**
   * Refuses the offer provided by launching no tasks.
   * @param driver driver for declining the offer
   * @param offer the offer to decline
   */
  @VisibleForTesting
  protected void declineOffer(SchedulerDriver driver, Offer offer) {
    declineOffer(driver, offer, Filters.newBuilder().build());
  }

  protected void declineOffer(SchedulerDriver driver, Offer offer, Filters filters) {
    driver.declineOffer(offer.getId(), filters);
  }

  public void reconcileTask(String taskID) {
    TaskID id = TaskID.newBuilder().setValue(taskID).build();
    TaskStatus taskStatus = TaskStatus.newBuilder().setTaskId(id).setState(TaskState.TASK_LOST).build();
    driver.reconcileTasks(Collections.singletonList(taskStatus));
  }

  public void reconcileAllTasks() {
    for(TaskID task : results.keySet()) {
      reconcileTask(task.getValue());
    }
  }

  private String stringifyTaskIds(Set<TaskInfo> taskInfos) {
    StringBuilder taskIdsBuilder = new StringBuilder();

    for (TaskInfo taskInfo : taskInfos) {
      taskIdsBuilder.append(taskInfo.getTaskId().getValue()).append(",");
    }

    return StringUtils.removeEnd(taskIdsBuilder.toString(), ",");
  }

  protected void launchMesosTasks(SchedulerDriver driver, List<Protos.OfferID> offerIds, Map<Protos.TaskInfo, Request> assignments, String hostname) {
    LOGGER.fine("Launching tasks: " + stringifyTaskIds(assignments.keySet()));

    Protos.Filters filters = Protos.Filters.newBuilder().setRefuseSeconds(1).build();
    driver.launchTasks(offerIds, assignments.keySet(), filters);

    // "transition" to finished
    for (Map.Entry<Protos.TaskInfo, Request> assignment : assignments.entrySet()) {
      Protos.TaskInfo taskInfo = assignment.getKey();
      Request request = assignment.getValue();

      List<Protos.ContainerInfo.DockerInfo.PortMapping> actualDockerPortMappings = taskInfo.getContainer().getDocker().getPortMappingsList();
      Set<MesosSlaveInfo.PortMapping> actualPortMappings = new LinkedHashSet<>();
      for (Protos.ContainerInfo.DockerInfo.PortMapping actualDockerPortMapping : actualDockerPortMappings) {
        MesosSlaveInfo.PortMapping requestedPortMapping = request.getRequest().getSlave().getPortMapping(actualDockerPortMapping.getContainerPort());

        actualPortMappings.add(new MesosSlaveInfo.PortMapping(
                actualDockerPortMapping.getContainerPort(),
                actualDockerPortMapping.getHostPort(),
                actualDockerPortMapping.getProtocol(),
                requestedPortMapping.getDescription(),
                requestedPortMapping.getUrlFormat(),
                requestedPortMapping.isStaticHostPort()
        ));
      }

      JenkinsSlave.ResultJenkinsSlave resultJenkinsSlave =
              new JenkinsSlave.ResultJenkinsSlave(request.getRequest().getSlave(), hostname, actualPortMappings);

      Protos.TaskID taskId = taskInfo.getTaskId();
      addResult(taskId, new Result(request.getResult(), resultJenkinsSlave));
      finishTask(taskId);
    }

  }


  // scheduler overrides
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

    resourceOffersImpl(driver, offers);
  }

  protected abstract void resourceOffersImpl(SchedulerDriver driver, List<Offer> offers);


  @VisibleForTesting
  void setDriver(SchedulerDriver driver) {
    this.driver = driver;
  }

  @Override
  public void offerRescinded(SchedulerDriver driver, OfferID offerId) {
    LOGGER.info("Rescinded offer " + offerId);
  }

  private MesosSlave asMesosAgent(Node node) {
    if (node instanceof MesosSlave) {
      return (MesosSlave) node;
    }

    return null;
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
    MesosSlave mesosSlave = asMesosAgent(Jenkins.get().getNode(taskId.getValue()));
    if (mesosSlave != null) {
      mesosSlave.setTaskStatus(status);
    }

    Result result = results.get(taskId);
    boolean terminalState = false;

    SlaveResult slaveResult = result.getResult();
    JenkinsSlave.ResultJenkinsSlave resultSlave = result.getSlave();

    switch (status.getState()) {
      case TASK_STAGING:
      case TASK_STARTING:
        break;
      case TASK_RUNNING:
        slaveResult.running(resultSlave);
        if(mesosSlave != null && StringUtils.isBlank(mesosSlave.getDockerContainerID())) {
          mesosSlave.setDockerContainerID(extractContainerIdFromTaskStatus(status));
        }
        break;
      case TASK_FINISHED:
        slaveResult.finished(resultSlave);
        terminalState = true;
        break;
      case TASK_KILLED:
        terminalState = true;
        break;
      case TASK_FAILED:
        slaveResult.failed(resultSlave, SlaveResult.FAILED_CAUSE.MESOS_CLOUD_REPORTED_TASK_FAILED);
        terminalState = true;
        break;
      case TASK_ERROR:
        slaveResult.failed(resultSlave, SlaveResult.FAILED_CAUSE.MESOS_CLOUD_REPORTED_TASK_ERROR);
        terminalState = true;
        break;
      case TASK_LOST:
        slaveResult.failed(resultSlave, SlaveResult.FAILED_CAUSE.MESOS_CLOUD_REPORTED_TASK_LOST);
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

  // getter setter and the likes
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

  /**
   * Checks if the given taskId already exists or just finished running. If it has, then refuse the offer.
   * @param taskId The task id
   * @return True if the task already exists, false otherwise
   */
  @VisibleForTesting
  private boolean isExistingTask(TaskID taskId) {
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

  private boolean isExistingAgent(Protos.TaskID taskId) {
    for (final Computer computer : Jenkins.get().getComputers()) {
      if (!(computer instanceof MesosComputer)) {
        LOGGER.finest("Not a mesos computer, skipping");
        continue;
      }

      MesosComputer mesosComputer = (MesosComputer) computer;

      MesosSlave mesosSlave = mesosComputer.getNode();
      if (mesosSlave != null) {
        if (StringUtils.equals(taskId.getValue(), computer.getName()) && mesosSlave.isPendingDelete()) {
          LOGGER.fine("This mesos task " + taskId.getValue() + " is pending deletion. Not launching another task");
          return true;
        }
      } else {
        LOGGER.warning("Unable to get node object for computer '" + computer + "'");
      }
    }

    return false;
  }

  protected boolean isExistingTaskOrAgent(TaskID taskId) {
    return isExistingTask(taskId) || isExistingAgent(taskId);
  }

  protected boolean isExistingTaskOrAgent(String agentName) {
    return isExistingTaskOrAgent(TaskID.newBuilder().setValue(agentName).build());
  }


  protected List<Request> drainRequests() {
    List<Request> currentRequests = new ArrayList<>(requests.size());
    requests.drainTo(currentRequests);
    return currentRequests;
  }

  protected void enqueueRequest(@Nonnull Request request) {
    try {
      requests.add(request);
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Unable to enqueue request '" + request + "':", e);
    }
  }

  protected void enqueueRequests(@Nonnull List<Request> requests) {
    for (Request request : requests) {
      enqueueRequest(request);
    }
  }

  public List<Request> getRequestsMatchingLabel(Label label) {
    List<Request> foundRequests = new ArrayList<>();

    for (Request request : requests) {
      try {
        String requestedLabelString = request.getRequest().getSlaveInfo().getLabelString();

        if (StringUtils.equals(requestedLabelString, label.getDisplayName())) {
          foundRequests.add(request);
        }
      } catch (Exception e) {
          LOGGER.log(Level.WARNING, "Error while trying to find a request matching with label '" + label + "'", e);
      }
    }

    return foundRequests;
  }

  public Request getRequestForLinkedItem(String linkedItem) {
    try {
      for (Request request : requests) {
        String requestedLinkedItem = request.getRequest().getSlave().getLinkedItem();
        if (StringUtils.equals(requestedLinkedItem, linkedItem)) {
          return request;
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error while finding request for '" + linkedItem + "':", e);
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
    return results.get(taskId);
  }

  protected void addResult(Protos.TaskID taskId, Result result) {
    results.put(taskId, result);
  }

  public void clearResults() {
    results.clear();
  }

  protected void finishTask(Protos.TaskID taskId) {
    finishedTasks.add(taskId);
  }

  private boolean isCpuLimitActivated() {
    // TODO: do activate with boolean value, not with certain value
    return mesosCloud.getMaxCpus() > 0;
  }

  private boolean isCpuLimitReached(Request request) {
    double futureUsedCpus = getUsedCpus() + request.getRequest().getSlave().getCpus();
    return isCpuLimitActivated() && mesosCloud.getMaxCpus() < futureUsedCpus;
  }

  private boolean isMemoryLimitActivated() {
    // TODO: do activate with boolean value, not with certain value
    return mesosCloud.getMaxMem() > 0;
  }

  private boolean isMemoryLimitReached(Request request) {
    double futureUsedMemory = getUsedMem() + request.getRequest().getSlave().getMem();
    return isMemoryLimitActivated() && mesosCloud.getMaxMem() < futureUsedMemory;
  }

  private boolean isResourceLimitReached(Request request) {
    return isCpuLimitReached(request) || isMemoryLimitReached(request);
  }

  public double getUsedCpus() {
    double cpus = 0.0;
    for(Request request: requests) {
      cpus += request.getRequest().getSlave().getCpus();
    }

    for(Result result: results.values()) {
      cpus += result.getSlave().getCpus();
    }
    return cpus;
  }

  public double getUsedMem() {
    double mem = 0.0;
    for(Request request: requests) {
      mem += request.getRequest().getSlave().getMem();
    }

    for(Result result: results.values()) {
      mem += result.getSlave().getMem();
    }
    return mem;
  }

  protected double getNoRequestsDeclineOfferDuration() {
    return DEFAULT_NO_REQUESTS_DECLINE_OFFER_DURATION;
  }

  public int getNumberOfPendingTasks() {
    return requests.size();
  }

  public int getNumberOfActiveTasks() {
    return results.size();
  }

  public MesosCloud getMesosCloud() {
    return mesosCloud;
  }

  protected void setMesosCloud(MesosCloud mesosCloud) {
    this.mesosCloud = mesosCloud;
  }

  public String getJenkinsMaster() {
    return jenkinsMaster;
  }

  public void setJenkinsMaster(String jenkinsMaster) {
    this.jenkinsMaster = jenkinsMaster;
  }

}
