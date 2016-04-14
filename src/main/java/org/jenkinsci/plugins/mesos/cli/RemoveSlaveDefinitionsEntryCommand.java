package org.jenkinsci.plugins.mesos.cli;

import hudson.Extension;
import hudson.cli.CLICommand;
import hudson.model.Failure;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.Messages;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveDefinitions;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.SlaveDefinitionsConfiguration;
import org.kohsuke.args4j.Argument;

@Extension
public class RemoveSlaveDefinitionsEntryCommand extends CLICommand {

  private Jenkins jenkins;
  private SlaveDefinitionsConfiguration.DescriptorImpl descriptor;

  @SuppressWarnings("unused")
  public RemoveSlaveDefinitionsEntryCommand() {
    this(Jenkins.getInstance());
  }

  /*package*/ RemoveSlaveDefinitionsEntryCommand(Jenkins jenkins) {
    this.jenkins = jenkins;
    this.descriptor = (SlaveDefinitionsConfiguration.DescriptorImpl)jenkins.getDescriptorOrDie(SlaveDefinitionsConfiguration.class);
  }

  @SuppressWarnings("unused")
  @Argument(metaVar = "DEFINITIONSNAME", usage = "The name of the definitions entry to remove", required = true)
  public String definitionsName;

  @Override
  public String getShortDescription() {
    return Messages.RemoveSlaveDefinitionsEntryCommand_ShortDescription();
  }

  @Override
  protected synchronized int run() throws Exception {
    jenkins.checkPermission(Jenkins.ADMINISTER);

    try {
      MesosSlaveDefinitions removedSlaveDefinitions = descriptor.removeSlaveDefinitionsEntry(definitionsName);

      if (removedSlaveDefinitions != null) {
        stdout.println(Messages.MesosApi_SuccessfullyRemovedSlaveDefinitionsEntry(removedSlaveDefinitions.toString()));
      } else {
        stdout.println(Messages.MesosApi_NotRemovedSlaveDefinitionsEntry(definitionsName));
      }
    } catch (Failure e) {
      stderr.println(e.getMessage());
      e.printStackTrace(stderr);
      return -1;
    }

    return 0;
  }
}
