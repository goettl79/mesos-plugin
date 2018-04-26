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
public class RemoveACLEntryCommand extends CLICommand {

  private Jenkins jenkins;
  private MesosFrameworkToItemMapper.DescriptorImpl descriptor;

  @SuppressWarnings("unused")
  public RemoveACLEntryCommand() {
    this(Jenkins.getInstance());
  }

  /*package*/ RemoveACLEntryCommand(Jenkins jenkins) {
    this.jenkins = jenkins;
    this.descriptor = (MesosFrameworkToItemMapper.DescriptorImpl)jenkins.getDescriptorOrDie(MesosFrameworkToItemMapper.class);
  }

  @SuppressWarnings("unused")
  @Argument(metaVar = "ITEMPATTERN", usage = "Name of the framework which should be used as new default framework name", required = true)
  public String itemPattern;

  @Override
  public String getShortDescription() {
    return Messages.RemoveACLEntryCommand_ShortDescription();
  }

  @Override
  protected synchronized int run() throws Exception {
    jenkins.checkPermission(Jenkins.ADMINISTER);

    try {
      ACLEntry aclEntry = descriptor.removeACLEntry(itemPattern);
      if (aclEntry != null) {
        stdout.println(Messages.MesosApi_SuccessfullyRemovedACLEntry(aclEntry.toString()));
      } else {
        stdout.println(Messages.MesosApi_NotRemovedACLEntry(itemPattern));
      }
    } catch (Failure e) {
      stderr.println(e.getMessage());
      e.printStackTrace(stderr);
      return -1;
    }

    return 0;
  }

}
