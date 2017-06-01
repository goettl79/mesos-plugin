package org.jenkinsci.plugins.mesos.config.slavedefinitions;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Failure;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.mesos.Mesos;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.Messages;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

@ExportedBean
public class SlaveDefinitionsConfiguration implements Describable<SlaveDefinitionsConfiguration> {

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

    private boolean slaveDefinitionsEntryExists(String slaveDefinitionsName) {
      return getSlaveInfos(slaveDefinitionsName) != null;
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
      this.slaveDefinitionsEntries = checkSlaveDefinitionsEntries(slaveDefinitionsEntries);
      save();
      return true;
    }


    public synchronized MesosSlaveDefinitions addSlaveDefinitionsEntry(String definitionsName, InputStream xml) {
      Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

      if (slaveDefinitionsEntryExists(definitionsName)) {
        throw new Failure(Messages.SlaveDefinitionsConfiguration_DefinitionsNameAlreadyExists(definitionsName));
      }

      return addOrUpdateSlaveDefinitionsEntry(definitionsName, xml);
    }

    public synchronized MesosSlaveDefinitions updateSlaveDefinitionsEntry(String definitionsName, InputStream xml) {
      Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

      if (!slaveDefinitionsEntryExists(definitionsName)) {
        throw new Failure(Messages.SlaveDefinitionsConfiguration_DefinitionsDoesNotExist(definitionsName));
      }

      return addOrUpdateSlaveDefinitionsEntry(definitionsName, xml);
    }

    public synchronized MesosSlaveDefinitions removeSlaveDefinitionsEntry(String definitionsName) {
      Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

      List<MesosSlaveDefinitions> futureSlaveDefinitionsEntries = new ArrayList<MesosSlaveDefinitions>(slaveDefinitionsEntries);

      Iterator<MesosSlaveDefinitions> it = futureSlaveDefinitionsEntries.iterator();

      MesosSlaveDefinitions removedDefinitionsEntry = null;
      while (it.hasNext() && removedDefinitionsEntry == null) {
        MesosSlaveDefinitions definitionsEntry = it.next();
        if (StringUtils.equals(definitionsEntry.getDefinitionsName(), definitionsName)) {
          removedDefinitionsEntry = definitionsEntry;
          it.remove();
        }
      }

      configure(futureSlaveDefinitionsEntries);

      return removedDefinitionsEntry;
    }

    private MesosSlaveDefinitions addOrUpdateSlaveDefinitionsEntry(String definitionsName, InputStream xml) {
      MesosSlaveDefinitions slaveDefinitionsEntryWithoutName = (MesosSlaveDefinitions) Jenkins.XSTREAM2.fromXML(xml);
      MesosSlaveDefinitions newSlaveDefinitionsEntry = new MesosSlaveDefinitions(definitionsName, slaveDefinitionsEntryWithoutName);

      List<MesosSlaveDefinitions> futureSlaveDefinitionsEntries = new ArrayList<MesosSlaveDefinitions>(slaveDefinitionsEntries);

      MesosSlaveDefinitions result;

      if (slaveDefinitionsEntryExists(newSlaveDefinitionsEntry.getDefinitionsName())) {
        int i = futureSlaveDefinitionsEntries.indexOf(newSlaveDefinitionsEntry);
        result = futureSlaveDefinitionsEntries.set(i, newSlaveDefinitionsEntry);
      } else {
        futureSlaveDefinitionsEntries.add(newSlaveDefinitionsEntry);
        result = newSlaveDefinitionsEntry;
      }

      configure(futureSlaveDefinitionsEntries);

      return result;
    }

    private boolean slaveDefinitionsEntriesContainsUsedEntry(String usedSlaveDefinitionsName, List<MesosSlaveDefinitions> slaveDefinitionsEntries) {
      for (MesosSlaveDefinitions slaveDefinitions : slaveDefinitionsEntries) {
        if (StringUtils.equals(usedSlaveDefinitionsName, slaveDefinitions.getDefinitionsName())) {
          return true;
        }
      }

      return false;
    }

    private void checkUsedSlaveDefinitionsRemovedOrUpdated(List<MesosSlaveDefinitions> slaveDefinitionsEntries) {

      List<String> errors = new ArrayList<String>();

      for (MesosCloud mesosCloud : Mesos.getAllMesosClouds()) {
        if (!slaveDefinitionsEntriesContainsUsedEntry(mesosCloud.getSlaveDefinitionsName(), slaveDefinitionsEntries)) {
          errors.add(Messages.MesosApi_NotRemovedInUseSlaveDefinitionsEntry(mesosCloud.getSlaveDefinitionsName(), mesosCloud.getFrameworkName()));
        }
      }

      if (!errors.isEmpty()) {
        throw new Failure(StringUtils.join(errors, ","));
      }
    }

    private List<MesosSlaveDefinitions> checkSlaveDefinitionsEntries(List<MesosSlaveDefinitions> slaveDefinitionsEntries) {
      if (slaveDefinitionsEntries == null) {
        throw new Failure(Messages.SlaveDefinitionsConfiguration_SpecifySlaveDefinitionsEntries());
      }

      checkUsedSlaveDefinitionsRemovedOrUpdated(slaveDefinitionsEntries);

      for (MesosSlaveDefinitions defsEntry : slaveDefinitionsEntries) {
        checkSlaveDefinitionsEntry(defsEntry);
        checkForMultipleSlaveDefinitionsEntiresWithSameName(defsEntry, slaveDefinitionsEntries);
      }

      return slaveDefinitionsEntries;
    }

    private MesosSlaveDefinitions checkSlaveDefinitionsEntry(MesosSlaveDefinitions defsEntry) {
      if (defsEntry == null) {
        throw new Failure(Messages.SlaveDefinitionsConfiguration_SpecifySlaveDefinitionsEntry());
      }

      checkDefinitionsName(defsEntry.getDefinitionsName());
      checkMesosSlaveInfos(defsEntry.getMesosSlaveInfos());

      return defsEntry;
    }

    private List<MesosSlaveInfo> checkMesosSlaveInfos(List<MesosSlaveInfo> mesosSlaveInfos) {
      if (mesosSlaveInfos == null) {
        throw new Failure(Messages.SlaveDefinitionsConfiguration_SpecifyMesosSlaveInfos());
      }

      return mesosSlaveInfos;
    }

    private String checkDefinitionsName(String definitionsName) {
      if (StringUtils.isBlank(definitionsName)) {
        throw new Failure(Messages.SlaveDefinitionsConfiguration_InvalidDefinitionsName());
      }

      return definitionsName;
    }

    @SuppressWarnings("unused")
    public FormValidation doCheckItemPattern(@QueryParameter String definitionsName) {
      try {
        checkDefinitionsName(definitionsName);
        return FormValidation.ok();
      } catch (Failure e) {
        return FormValidation.error(e.getMessage());
      }
    }

    private MesosSlaveDefinitions checkForMultipleSlaveDefinitionsEntiresWithSameName(MesosSlaveDefinitions defsEntry, List<MesosSlaveDefinitions> slaveDefinitionsEntries) {
      int count = 0;
      for (MesosSlaveDefinitions otherDefsEntry : slaveDefinitionsEntries) {
        if (StringUtils.equals(defsEntry.getDefinitionsName(), otherDefsEntry.getDefinitionsName())) {
          count++;
        }
      }

      if (count > 1) {
        throw new Failure(Messages.SlaveDefinitionsConfiguration_DefinitionsNameAlreadyExists(defsEntry.getDefinitionsName()));
      }

      return defsEntry;
    }

  }

  @Exported(inline = true, visibility = 1)
  public List<MesosSlaveDefinitions> getSlaveDefinitionsEntries() {
    return getDescriptorImpl().getSlaveDefinitionsEntries();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Descriptor<SlaveDefinitionsConfiguration> getDescriptor() {
    return Jenkins.getInstance().getDescriptorOrDie(getClass());
  }

  private DescriptorImpl getDescriptorImpl() {
    return (DescriptorImpl) getDescriptor();
  }

  public static DescriptorImpl getDescriptorImplStatic() {
    return (DescriptorImpl)Jenkins.getInstance().getDescriptorOrDie(SlaveDefinitionsConfiguration.class);
  }
}
