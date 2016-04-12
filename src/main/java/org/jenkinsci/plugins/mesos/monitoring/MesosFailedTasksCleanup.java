package org.jenkinsci.plugins.mesos.monitoring;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

@Extension
public class MesosFailedTasksCleanup extends AsyncPeriodicWork {

  public MesosFailedTasksCleanup() {
    super("Failed Mesos Tasks Handler");
  }

  @Override
  public long getRecurrencePeriod() {
    return MIN * 2;
  }

  public static void invoke() {
    getInstance().run();
  }

  private static MesosFailedTasksCleanup getInstance() {
    return Jenkins.getInstance().getExtensionList(AsyncPeriodicWork.class).get(MesosFailedTasksCleanup.class);
  }

  @Override
  protected void execute(TaskListener listener) {
    try {
      MesosTaskFailureMonitor.getInstance().fix(listener);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
