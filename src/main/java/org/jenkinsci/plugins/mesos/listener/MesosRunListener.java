package org.jenkinsci.plugins.mesos.listener;

import hudson.Extension;
import hudson.model.*;
import hudson.model.listeners.RunListener;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.mesos.JenkinsScheduler;
import org.jenkinsci.plugins.mesos.Mesos;
import org.jenkinsci.plugins.mesos.MesosSlave;
import org.jenkinsci.plugins.mesos.actions.MesosBuiltOnAction;
import org.jenkinsci.plugins.mesos.actions.MesosBuiltOnProperty;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@SuppressWarnings({"rawtypes", "unused"})
@Extension
public class MesosRunListener extends RunListener<Run> {

  private static final Logger LOGGER = Logger.getLogger(MesosRunListener.class.getName());
  private static final String EXCLUDED_CLASSES_FROM_LOG_OUTPUT_REGEX = "hudson\\.maven\\.MavenBuild";

  public MesosRunListener() {
      super();
  }

  @SuppressWarnings("unchecked")
  public MesosRunListener(Class targetType) {
    super(targetType);
  }


  private MesosBuiltOnAction createBuiltOnAction(MesosSlave mesosJenkinsAgent) {
    JenkinsScheduler jenkinsScheduler = (JenkinsScheduler) Mesos.getInstance(mesosJenkinsAgent.getCloud()).getScheduler();

    String mesosAgentHostname = StringUtils.defaultIfBlank(jenkinsScheduler.getResult(mesosJenkinsAgent.getDisplayName()).getSlave().getHostname(), "N/A");

    String jenkinsAgentHostname = "N/A";
    try {
        Computer computer = mesosJenkinsAgent.toComputer();
        if (computer != null) {
            jenkinsAgentHostname = StringUtils.defaultIfBlank(computer.getHostName(), jenkinsAgentHostname);
        }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error while trying to get hostname of node", e);
      jenkinsAgentHostname = "N/A";
    }

    String containerId = StringUtils.defaultIfBlank(mesosJenkinsAgent.getDockerContainerID(), "N/A");

    return new MesosBuiltOnAction(mesosAgentHostname, jenkinsAgentHostname, containerId);
  }

  /**
   * Prints the actual Hostname where Mesos slave is provisioned in console output.
   * This would help us debug/take action if build fails in that slave.
   */
  @Override
  public void onStarted(Run run, TaskListener listener) {
    if (doNotSkipLogfileOutput(run)) {
      Node node = getCurrentNode(run);
      if (node instanceof MesosSlave) {
        MesosBuiltOnAction builtOnAction = createBuiltOnAction((MesosSlave)node);

        // add to current build
        run.replaceAction(builtOnAction);

        StringBuilder msgBuilder = new StringBuilder()
                .append("\nThis build is running on:\n")
                .append("  * Mesos Agent:   ").append(builtOnAction.getMesosAgentHostname()).append("\n")
                .append("  * Jenkins Agent: ").append(builtOnAction.getJenkinsAgentHostname()).append("\n")
                .append("  * Container ID:  ").append(builtOnAction.getContainerId()).append("\n");

        // always add, or only if was built on mesos node?
        // b/c: sometimes could be configured to run on mesos node, and other times not
        try {
          if (run.getParent().getProperty(MesosBuiltOnProperty.class) == null) {
            run.getParent().addProperty(new MesosBuiltOnProperty());
          }
        } catch (Exception e) {
          LOGGER.log(Level.WARNING, "Failed to add MesosBuiltOnProperty to " + run.getParent().getFullDisplayName(), e);
          msgBuilder.append("WARNING! Failed to add the built-on property to this job.\n");
        }

        listener.getLogger().println(msgBuilder.toString());
      }
    }
  }

  @Override
  public void onCompleted(Run run, @Nonnull TaskListener listener) {
    if (doNotSkipLogfileOutput(run)) {
      Node node = getCurrentNode(run);
      if (isGrafanaDashboardLinkConfigured(node)) {
        MesosSlave mesosSlave = (MesosSlave) node;
        String monitoringUrl = mesosSlave.getMonitoringURL();
        if (monitoringUrl != null) {
          listener.getLogger().println("\nSlave resource usage: " + monitoringUrl + "\n");
        } else {
          listener.error("\nSlave resource usage is not available for this build.\n");
        }
      }
    }
  }

  private boolean isGrafanaDashboardLinkConfigured(Node node) {
    return node instanceof MesosSlave && !StringUtils.isBlank(((MesosSlave) node).getCloud().getGrafanaDashboardURL());
  }

  private boolean doNotSkipLogfileOutput(Run r) {
    return !(r == null || Pattern.matches(EXCLUDED_CLASSES_FROM_LOG_OUTPUT_REGEX, r.getClass().getName()));
  }

  /**
   * Returns the current {@link Node} on which we are building.
   */
  @CheckForNull
  private Node getCurrentNode(Run run) {
    Executor executor = run.getExecutor();
    if (executor == null) {
      return null;
    }

    return executor.getOwner().getNode();
  }

}
