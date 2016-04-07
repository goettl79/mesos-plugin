package org.jenkinsci.plugins.mesos.acl;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.ManagementLink;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@ExportedBean
public class MesosFrameworkToItemMapper {

  private final List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();

  @DataBoundConstructor
  public MesosFrameworkToItemMapper() {
    // TODO: remove values with real configuration
    aclEntries.add(new ACLEntry("ProductCore/.*", "core"));
    aclEntries.add(new ACLEntry("EasyTax/.*", "ase"));
    aclEntries.add(new ACLEntry("DevOpss/.*", "opss"));
    aclEntries.add(new ACLEntry("MyFolder/.*", "My Mesos Framework"));
  }

  @Exported
  public List<ACLEntry> getAclEntries() {
    return Collections.unmodifiableList(aclEntries);
  }

  public String findFrameworkName(String itemName) {
    for (ACLEntry entry: aclEntries) {
      if (itemName.matches(entry.getItemPattern())) {
        return entry.getFrameworkName();
      }
    }

    return null;
  }
}
