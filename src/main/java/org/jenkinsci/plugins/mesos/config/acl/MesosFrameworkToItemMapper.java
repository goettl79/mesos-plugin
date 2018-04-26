package org.jenkinsci.plugins.mesos.config.acl;

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

import javax.annotation.Nonnull;
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

    private List<ACLEntry> aclEntries = new ArrayList<>();
    private String defaultFrameworkName = "Deny";

    public DescriptorImpl() {
      load();
    }

    @Nonnull
    @Override
    public String getDisplayName() {
      return "FrameworkToItemMapper";
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

    /**
     * Adds and ACL entry with the specified item pattern and name of the framework to the configured ACL entries.
     *
     * @param itemPattern Item pattern which matches the names of the items to build
     * @param frameworkName Name of the framework where the matching items should provision a Mesos Task
     * @return the newly generated ACL entry if adding it to the ACL entries was successful
     */
    public ACLEntry addACLEntry(String itemPattern, String frameworkName) {
      List<ACLEntry> futureACLEntries = new ArrayList<>(this.aclEntries);

      ACLEntry newACLEntry = new ACLEntry(itemPattern, frameworkName);
      futureACLEntries.add(newACLEntry);

      configure(futureACLEntries, this.defaultFrameworkName);

      return newACLEntry;
    }

    /**
     * Removes an ACL entry with equal item pattern from the configured ACL entries.
     *
     * @param itemPattern Item pattern which has to be equal to the pattern of the entry to delete
     * @return the deleted ACL entry or <tt>null</tt> if no matching entry was found
     */
    public ACLEntry removeACLEntry(String itemPattern) {
      ACLEntry removedACLEntry = null;
      List<ACLEntry> futureACLEntries = new ArrayList<>(this.aclEntries.size());
      futureACLEntries.addAll(this.aclEntries);

      Iterator<ACLEntry> it = futureACLEntries.iterator();
      while (it.hasNext() && removedACLEntry == null) {
        ACLEntry aclEntry = it.next();
        if (StringUtils.equals(aclEntry.getItemPattern(), itemPattern)) {
          removedACLEntry = aclEntry;
          it.remove();
        }
      }

      if (removedACLEntry != null) {
        configure(futureACLEntries, this.defaultFrameworkName);
      }

      return removedACLEntry;
    }

    /**
     * Changes the default framework name to the specified framework name.
     *
     * @param frameworkName The new name of the default framework
     * @return the name of the old default framework
     */
    public String changeDefaultFrameworkName(String frameworkName) {
      String oldDefaultFrameworkName = this.defaultFrameworkName;

      if (!StringUtils.equals(oldDefaultFrameworkName, frameworkName)) {
        configure(this.aclEntries, frameworkName);
      }

      return oldDefaultFrameworkName;
    }

    private List<ACLEntry> checkACLEntries(List<ACLEntry> aclEntries) {
      if (aclEntries == null) {
        throw new Failure(Messages.MesosFrameworkToItemMapper_SpecifyACLEntries());
      }


      for (ACLEntry aclEntry : aclEntries) {
        checkForMultipleACLEntriesWithEqualItemPattern(aclEntry, aclEntries);
        checkACLEntry(aclEntry);
      }

      return aclEntries;
    }

    private ACLEntry checkForMultipleACLEntriesWithEqualItemPattern(ACLEntry aclEntry, List<ACLEntry> aclEntries) {
      int count = 0;
      for (ACLEntry aclEntryFromList : aclEntries) {
        if (StringUtils.equals(aclEntryFromList.getItemPattern(), aclEntry.getItemPattern())) {
          count++;
        }
      }

      if (count > 1) {
        throw new Failure(Messages.MesosFrameworkToItemMapper_MultipleACLEntriesWithEqualPattern(aclEntry.getItemPattern()));
      }

      return aclEntry;
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


    /**
     * Returns the Mesos Framework name which is mapped to the specified item with an {@link ACLEntry}.<br>
     * In case the item is not mapped or not matched against the patterns of the entries, it returns the default Framework
     * to use for the item.
     *
     * @param itemName Specifies the name of the item
     * @return the mapped Framework name or the default Framework name
     */
    public String findFrameworkName(String itemName) {
      if (StringUtils.isBlank(itemName)) {
        throw new IllegalArgumentException(Messages.MesosFrameworkToItemMapper_InvalidItemName(itemName));
      }

      for (ACLEntry entry: aclEntries) {
        if (itemName.matches(entry.getItemPattern())) {
          return entry.getFrameworkName();
        }
      }

      return getDefaultFrameworkName();
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

  public String findFrameworkName(String itemName) {
    return getDescriptorImpl().findFrameworkName(itemName);
  }

  @Override
  public Descriptor<MesosFrameworkToItemMapper> getDescriptor() {
    return Jenkins.getInstance().getDescriptorOrDie(getClass());
  }

  public DescriptorImpl getDescriptorImpl() {
    return (DescriptorImpl) getDescriptor();
  }
}
