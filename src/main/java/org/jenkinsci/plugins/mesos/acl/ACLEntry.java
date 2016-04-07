package org.jenkinsci.plugins.mesos.acl;


import org.kohsuke.stapler.DataBoundConstructor;

public class ACLEntry {

  private final String itemPattern;
  private final String frameworkName;


  @DataBoundConstructor
  public ACLEntry(String itemPattern, String frameworkName) {
    this.itemPattern = itemPattern;
    this.frameworkName = frameworkName;
  }

  public String getItemPattern() {
    return itemPattern;
  }

  public String getFrameworkName() {
    return frameworkName;
  }


}
