package org.jenkinsci.plugins.mesos.listener;

import hudson.Extension;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.mesos.MesosSlave;
import org.jenkinsci.plugins.mesos.actions.MesosBuiltOnAction;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
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


  /**
   * Prints the actual Hostname where Mesos slave is provisioned in console output.
   * This would help us debug/take action if build fails in that slave.
   */
  @Override
  public void onStarted(Run run, TaskListener listener) {
    if (doNotSkipLogfileOutput(run)) {
      Node node = getCurrentNode(run);
      if (node instanceof MesosSlave) {

        MesosBuiltOnAction builtOnAction = run.getAction(MesosBuiltOnAction.class);

        StringBuilder msgBuilder = new StringBuilder();
        if (builtOnAction != null) {
          msgBuilder
            .append("\nThis build is running on:\n")
            .append("  * Mesos Agent:   ").append(builtOnAction.getMesosAgentHostname()).append("\n")
            .append("  * Jenkins Agent: ").append(builtOnAction.getJenkinsAgentHostname()).append("\n")
            .append("  * Container ID:  ").append(builtOnAction.getContainerId()).append("\n");
        } else {
          msgBuilder.append("\nUnable to determine where this build runs on (missing action)\n");
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
