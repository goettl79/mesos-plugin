package org.jenkinsci.plugins.mesos.listener;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Logger;

import org.jenkinsci.plugins.mesos.JenkinsScheduler;
import org.jenkinsci.plugins.mesos.Mesos;
import org.jenkinsci.plugins.mesos.MesosSlave;

@SuppressWarnings("rawtypes")
@Extension
public class MesosRunListener extends RunListener<Run> {

  private static final Logger LOGGER = Logger.getLogger(MesosRunListener.class.getName());

  public MesosRunListener() {

  }

  /**
   * @param targetType
   */
  @SuppressWarnings("unchecked")
  public MesosRunListener(Class targetType) {
    super(targetType);
  }

  /**
   * Prints the actual Hostname where Mesos slave is provisioned in console output.
   * This would help us debug/take action if build fails in that slave.
   */
  @Override
  public void onStarted(Run r, TaskListener listener) {
    if (r instanceof AbstractBuild) {
      Node node = getCurrentNode();
      if (node instanceof MesosSlave) {
        try {
          MesosSlave mesosSlave = (MesosSlave) node;
          JenkinsScheduler jenkinsScheduler = (JenkinsScheduler) Mesos.getInstance(mesosSlave.getCloud()).getScheduler();

          String mesosNodeHostname = jenkinsScheduler.getResult(mesosSlave.getDisplayName()).getSlave().getHostName();
          String hostname = node.toComputer().getHostName();

          PrintStream logger = listener.getLogger();
          logger.println();
          logger.println(String.format("This build is running on %s in %s", mesosNodeHostname, mesosSlave.getDockerContainerID()));

          if(hostname != null) {
            logger.println("Jenkins Slave (hostname): " + hostname);
          }
          logger.println();

        } catch (IOException e) {
          LOGGER.warning("IOException while trying to get hostname: " + e);
          e.printStackTrace();
        } catch (InterruptedException e) {
          LOGGER.warning("InterruptedException while trying to get hostname: " + e);
        }
      }
    }
  }

  @Override
  public void onCompleted(Run run, TaskListener listener) {
    if (run instanceof AbstractBuild) {
      Node node = getCurrentNode();
      if (node instanceof MesosSlave) {
        MesosSlave mesosSlave = (MesosSlave) node;

        if(mesosSlave.getMonitoringURL() != null) {
          PrintStream logger = listener.getLogger();
          logger.println();
          logger.println("Slave resource usage: " + mesosSlave.getMonitoringURL());
          logger.println();
        }
      }
    }
  }

  /**
   * Returns the current {@link Node} on which we are building.
   */
  private final Node getCurrentNode() {
    return Executor.currentExecutor().getOwner().getNode();
  }

}
