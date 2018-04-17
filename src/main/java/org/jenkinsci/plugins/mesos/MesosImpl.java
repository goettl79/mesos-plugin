package org.jenkinsci.plugins.mesos;

import org.apache.mesos.Scheduler;
import org.jenkinsci.plugins.mesos.scheduling.SlaveRequest;
import org.jenkinsci.plugins.mesos.scheduling.SlaveResult;

public class MesosImpl extends Mesos {
  @Override
  public synchronized void startScheduler(String jenkinsMaster, MesosCloud mesosCloud) {
    stopScheduler();

    // if config == useLegacy/Old
    scheduler = new JenkinsSchedulerOld(jenkinsMaster, mesosCloud);
    // else
    // scheduler = new JenkinsSchedulerNew(jenkinsMasdter, mesosCloud);

    scheduler.init();
  }

  @Override
  public synchronized boolean isSchedulerRunning() {
    return scheduler != null && scheduler.isRunning();
  }

  @Override
  public synchronized void stopScheduler() {
    if (scheduler != null) {
      scheduler.stop();
      scheduler = null;
    }
  }

  @Override
  public synchronized void startJenkinsSlave(SlaveRequest request, SlaveResult result) {
    if (scheduler != null) {
      scheduler.requestJenkinsSlave(request, result);
    }
  }

  @Override
  public synchronized void stopJenkinsSlave(String name) {
    if (scheduler != null) {
      scheduler.terminateJenkinsSlave(name);
    }
  }

  @Override
  public synchronized void updateScheduler(String jenkinsMaster, MesosCloud mesosCloud) {
    scheduler.setMesosCloud(mesosCloud);
    scheduler.setJenkinsMaster(jenkinsMaster);
  }

  private JenkinsScheduler scheduler;

  @Override
  public Scheduler getScheduler() {
    return scheduler;
  }

}
