package org.jenkinsci.plugins.mesos.acl;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Failure;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
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

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ExportedBean
public class MesosFrameworkToItemMapper implements Describable<MesosFrameworkToItemMapper> {

  private static final Logger LOGGER = Logger.getLogger(MesosFrameworkToItemMapper.class.getName());

  @Extension
  public static class DescriptorImpl extends Descriptor<MesosFrameworkToItemMapper> {

    private static final ListBoxModel.Option DENY_OPTION = new ListBoxModel.Option("Deny");

    private List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();
    private String defaultFrameworkName = "Deny";

    public DescriptorImpl() {
      load();
    }

    @Override
    public String getDisplayName() {
      return null;
    }

    @RequirePOST
    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws Descriptor.FormException {
      Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

      List<ACLEntry> futureACLEntires = null;
      if (json.has("aclEntries")) {
        Object aclEntriesJSON = json.get("aclEntries");
        futureACLEntires = req.bindJSONToList(ACLEntry.class, aclEntriesJSON);
      }

      String futureDefaultFrameworkName = json.getString("defaultFrameworkName");

      return configure(futureACLEntires, futureDefaultFrameworkName);
    }

    private boolean configure(List<ACLEntry> futureACLEntries, String futureDefaultFrameworkName) {
      if (futureACLEntries != null && !futureACLEntries.isEmpty()) {
        this.aclEntries = checkACLEntries(futureACLEntries);
      } else {
        this.aclEntries = Collections.emptyList();
      }

      this.defaultFrameworkName = checkFrameworkName(futureDefaultFrameworkName);

      save();
      return true;
    }

    private List<ACLEntry> checkACLEntries(List<ACLEntry> aclEntries) {
      if (aclEntries == null) {
        throw new Failure(Messages.MesosFrameworkToItemMapper_SpecifyACLEntries());
      }

      for (ACLEntry aclEntry : aclEntries) {
        checkACLEntry(aclEntry);
      }

      return aclEntries;
    }

    private ACLEntry checkACLEntry(ACLEntry aclEntry) {
      if (aclEntry == null) {
        throw new Failure(Messages.MesosFrameworkToItemMapper_SpecifyACLEntry());
      }

      checkItemPattern(aclEntry.getItemPattern());
      checkFrameworkName(aclEntry.getFrameworkName());

      return aclEntry;
    }

    private String checkItemPattern(String itemPattern) {
      if (StringUtils.isBlank(itemPattern)) {
        throw new Failure(Messages.MesosFrameworkToItemMapper_EmptyItemPattern());
      }

      try {
        Pattern.compile(itemPattern);
      } catch (PatternSyntaxException e) {
        String message = Messages.MesosFrameworkToItemMapper_InvalidItemPattern(itemPattern);
        LOGGER.log(Level.FINER, message, e);
        throw new Failure(message);
      }

      return itemPattern;
    }

    @SuppressWarnings("unused")
    public FormValidation doCheckItemPattern(@QueryParameter String itemPattern) {
      try {
        checkItemPattern(itemPattern);
        return FormValidation.ok();
      } catch (Failure e) {
        return FormValidation.error(e.getMessage());
      }
    }

    private String checkFrameworkName(String frameworkName) {
      ListBoxModel listBoxModel = doFillFrameworkNameItems(frameworkName);

      for (ListBoxModel.Option option : listBoxModel) {
        if (option.value.equals(frameworkName)) {
          return frameworkName;
        }
      }

      throw new Failure(Messages.MesosFrameworkToItemMapper_NonExistingFramework(frameworkName));
    }

    @SuppressWarnings("unused")
    public FormValidation doCheckFrameworkName(@QueryParameter String frameworkName) {
      try {
        checkFrameworkName(frameworkName);
        return FormValidation.ok();
      } catch (Failure e) {
        return FormValidation.error(e.getMessage());
      }
    }

    @SuppressWarnings("unused")
    public ListBoxModel doFillFrameworkNameItems(@QueryParameter String frameworkName) {
      ListBoxModel frameworkNameItems = new ListBoxModel();
      frameworkNameItems.add(DENY_OPTION);

      Collection<MesosCloud> mesosClouds =  Mesos.getAllMesosClouds();

      for (MesosCloud mesosCloud : mesosClouds) {
        String mesosCloudFrameworkName = mesosCloud.getFrameworkName();
        boolean selected = StringUtils.equals(mesosCloudFrameworkName, frameworkName);
        frameworkNameItems.add(new ListBoxModel.Option(mesosCloudFrameworkName, mesosCloudFrameworkName, selected));
      }

      return frameworkNameItems;
    }

    @SuppressWarnings("unused")
    public ListBoxModel doFillDefaultFrameworkNameItems(@QueryParameter String defaultFrameworkName) {
      return doFillFrameworkNameItems(defaultFrameworkName);
    }

    @SuppressWarnings("unused")
    public FormValidation doCheckDefaultFrameworkName(@QueryParameter String defaultFrameworkName) {
      return doCheckFrameworkName(defaultFrameworkName);
    }


    public List<ACLEntry> getACLEntries() {
      return Collections.unmodifiableList(aclEntries);
    }

    public String getDefaultFrameworkName() {
      return defaultFrameworkName;
    }
  }

  @Exported(inline = true, visibility = 1)
  @SuppressWarnings("unused")
  public List<ACLEntry> getACLEntries() {
    return getDescriptorImpl().getACLEntries();
  }

  @Exported
  @SuppressWarnings("unused")
  public String getDefaultFrameworkName() {
    return getDescriptorImpl().getDefaultFrameworkName();
  }

  /**
   * Returns the Mesos Framework name which is mapped to the specified item with an {@link ACLEntry}.<br />
   * In case the item is not mapped or not matched against the patterns of the entries, it returns the default Framework
   * to use for the item.
   *
   * @param itemName Specifies the name of the item
   * @return the mapped Framework name or the default Framework name
   */
  public String findFrameworkName(String itemName) {
    for (ACLEntry entry: getDescriptorImpl().getACLEntries()) {
      if (itemName.matches(entry.getItemPattern())) {
        return entry.getFrameworkName();
      }
    }

    return getDefaultFrameworkName();
  }

  @Override
  public Descriptor<MesosFrameworkToItemMapper> getDescriptor() {
    return Jenkins.getInstance().getDescriptorOrDie(getClass());
  }

  public DescriptorImpl getDescriptorImpl() {
    return (DescriptorImpl) getDescriptor();
  }
}
