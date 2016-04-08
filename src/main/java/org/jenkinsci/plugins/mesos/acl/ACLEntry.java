package org.jenkinsci.plugins.mesos.acl;


import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean
public class ACLEntry {

  private final String itemPattern;
  private final String frameworkName;


  @DataBoundConstructor
  public ACLEntry(String itemPattern, String frameworkName) {
    this.itemPattern = itemPattern;
    this.frameworkName = frameworkName;
  }

  @Exported
  public String getItemPattern() {
    return itemPattern;
  }

  @Exported
  public String getFrameworkName() {
    return frameworkName;
  }


}
