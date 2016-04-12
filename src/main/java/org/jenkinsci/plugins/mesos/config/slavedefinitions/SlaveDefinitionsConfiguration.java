package org.jenkinsci.plugins.mesos.config.slavedefinitions;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

@ExportedBean
public class SlaveDefinitionsConfiguration implements Describable<SlaveDefinitionsConfiguration> {

  private static final Logger LOGGER = Logger.getLogger(SlaveDefinitionsConfiguration.class.getName());

  @Extension
  public static class DescriptorImpl extends Descriptor<SlaveDefinitionsConfiguration> {

    List<MesosSlaveDefinitions> slaveDefinitionsEntries = new ArrayList<MesosSlaveDefinitions>();

    public DescriptorImpl() {
      load();
    }

    @Override
    public String getDisplayName() {
      return null;
    }

    public List<MesosSlaveDefinitions> getSlaveDefinitionsEntries() {
      return Collections.unmodifiableList(slaveDefinitionsEntries);
    }

    public List<MesosSlaveInfo> getSlaveInfos(String slaveDefinitionsName) {
      for (MesosSlaveDefinitions slaveDefinitionsEntry : slaveDefinitionsEntries) {
        if (StringUtils.equals(slaveDefinitionsName, slaveDefinitionsEntry.getDefinitionsName())) {
          return slaveDefinitionsEntry.getMesosSlaveInfos();
        }
      }

      return null;
    }

    @RequirePOST
    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
      Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

      List<MesosSlaveDefinitions> futureSlaveDefinitionsEntries = null;
      if (json.has("slaveDefinitionsEntries")) {
        Object slaveDefinitionsEntriesJSON = json.get("slaveDefinitionsEntries");
        futureSlaveDefinitionsEntries = req.bindJSONToList(MesosSlaveDefinitions.class, slaveDefinitionsEntriesJSON);
      }

      return configure(futureSlaveDefinitionsEntries);
    }

    public boolean configure(List<MesosSlaveDefinitions> slaveDefinitionsEntries) {
      this.slaveDefinitionsEntries = slaveDefinitionsEntries;

      save();
      return true;
    }
  }

  @Exported(inline = true, visibility = 1)
  public List<MesosSlaveDefinitions> getSlaveDefinitionsEntries() {
    return getDescriptorImpl().getSlaveDefinitionsEntries();
  }

  @Override
  public Descriptor<SlaveDefinitionsConfiguration> getDescriptor() {
    return Jenkins.getInstance().getDescriptorOrDie(getClass());
  }

  public DescriptorImpl getDescriptorImpl() {
    return (DescriptorImpl) getDescriptor();
  }

  public static DescriptorImpl getDescriptorImplStatic() {
    return (DescriptorImpl)Jenkins.getInstance().getDescriptorOrDie(SlaveDefinitionsConfiguration.class);
  }
}
