package org.jenkinsci.plugins.mesos.cli;

import hudson.Extension;
import hudson.cli.CLICommand;
import hudson.model.Failure;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.Messages;
import org.jenkinsci.plugins.mesos.config.acl.ACLEntry;
import org.jenkinsci.plugins.mesos.config.acl.MesosFrameworkToItemMapper;
import org.kohsuke.args4j.Argument;

@Extension
public class AddACLEntryCommand extends CLICommand {

  private Jenkins jenkins;
  private MesosFrameworkToItemMapper.DescriptorImpl descriptor;

  @SuppressWarnings("unused")
  public AddACLEntryCommand() {
    this(Jenkins.get());
  }

  /*package*/ AddACLEntryCommand(Jenkins jenkins) {
    this.jenkins = jenkins;
    this.descriptor = (MesosFrameworkToItemMapper.DescriptorImpl)jenkins.getDescriptorOrDie(MesosFrameworkToItemMapper.class);
  }

  @SuppressWarnings("unused")
  @Argument(metaVar = "ITEMPATTERN", usage = "Pattern (regular expression) of the item to match", required = true)
  public String itemPattern;

  @SuppressWarnings("unused")
  @Argument(metaVar = "FRAMEWORKNAME", usage = "Name of the (already configured) framework where the item should provision a task", required = true, index = 1)
  public String frameworkName;

  @Override
  public String getShortDescription() {
    return Messages.AddACLEntryCommand_ShortDescription();
  }

  @Override
  protected synchronized int run() throws Exception {
    jenkins.checkPermission(Jenkins.ADMINISTER);

    try {
      ACLEntry aclEntry = descriptor.addACLEntry(itemPattern, frameworkName);
      stdout.println(Messages.MesosApi_SuccessfullyAddedACLEntry(aclEntry.toString()));
    } catch (Failure e) {
      stderr.println(e.getMessage());
      e.printStackTrace(stderr);
      return -1;
    }

    return 0;
  }

}
