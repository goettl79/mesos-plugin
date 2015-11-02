package org.jenkinsci.plugins.mesos.monitoring;

import java.io.IOException;
import java.util.logging.Level;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.OfflineCause;
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