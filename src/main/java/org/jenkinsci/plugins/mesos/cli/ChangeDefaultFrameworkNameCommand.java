package org.jenkinsci.plugins.mesos.cli;

import hudson.Extension;
import hudson.cli.CLICommand;
import hudson.model.Failure;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.Messages;
import org.jenkinsci.plugins.mesos.acl.ACLEntry;
import org.jenkinsci.plugins.mesos.acl.MesosFrameworkToItemMapper;
import org.kohsuke.args4j.Argument;

@Extension
public class ChangeDefaultFrameworkNameCommand extends CLICommand {

  private Jenkins jenkins;
  private MesosFrameworkToItemMapper.DescriptorImpl descriptor;

  @SuppressWarnings("unused")
  public ChangeDefaultFrameworkNameCommand() {
    this(Jenkins.getInstance());
  }

  /*package*/ ChangeDefaultFrameworkNameCommand(Jenkins jenkins) {
    this.jenkins = jenkins;
    this.descriptor = (MesosFrameworkToItemMapper.DescriptorImpl)jenkins.getDescriptorOrDie(MesosFrameworkToItemMapper.class);
  }

  @SuppressWarnings("unused")
  @Argument(metaVar = "FRAMEWORKNAME", usage = "The exact item pattern of the specific ACL entry to delete", required = true)
  public String frameworkName;

  @Override
  public String getShortDescription() {
    return Messages.ChangeDefaultFrameworkNameCommand_ShortDescription();
  }

  @Override
  protected synchronized int run() throws Exception {
    jenkins.checkPermission(Jenkins.ADMINISTER);

    try {
      String oldFrameworkName = descriptor.changeDefaultFrameworkName(frameworkName);
      stdout.println(Messages.MesosApi_SuccessfullyChangedDefaultFrameworkName(oldFrameworkName, frameworkName));
    } catch (Failure e) {
      stderr.println(e.getMessage());
      e.printStackTrace(stderr);
      return -1;
    }

    return 0;
  }

}
