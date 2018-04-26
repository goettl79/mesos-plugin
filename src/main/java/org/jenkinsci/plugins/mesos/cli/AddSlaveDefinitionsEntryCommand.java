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
public class AddSlaveDefinitionsEntryCommand extends CLICommand {


  private Jenkins jenkins;
  private SlaveDefinitionsConfiguration.DescriptorImpl descriptor;

  @SuppressWarnings("unused")
  public AddSlaveDefinitionsEntryCommand() {
    this(Jenkins.get());
  }

  /*package*/ AddSlaveDefinitionsEntryCommand(Jenkins jenkins) {
    this.jenkins = jenkins;
    this.descriptor = (SlaveDefinitionsConfiguration.DescriptorImpl)jenkins.getDescriptorOrDie(SlaveDefinitionsConfiguration.class);
  }

  @SuppressWarnings("unused")
  @Argument(metaVar = "DEFINITIONSNAME", usage = "The name of the definitions entry to update", required = true)
  public String definitionsName;

  @Override
  public String getShortDescription() {
    return Messages.AddSlaveDefinitionsEntryCommand_ShortDescription();
  }

  @Override
  protected synchronized int run() throws Exception {
    jenkins.checkPermission(Jenkins.ADMINISTER);

    try {
      MesosSlaveDefinitions mesosSlaveDefinitions = descriptor.addSlaveDefinitionsEntry(definitionsName, stdin);
      stdout.println(Messages.MesosApi_SuccessfullyAddedSlaveDefinitions(mesosSlaveDefinitions.toString()));
    } catch (Failure e) {
      stderr.println(e.getMessage());
      e.printStackTrace(stderr);
      return -1;
    }

    return 0;
  }

}
