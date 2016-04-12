package org.jenkinsci.plugins.mesos.acl;


import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
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

  @Override
  public String toString() {
    return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
        .append("itemPattern", itemPattern)
        .append("frameworkName", frameworkName)
        .toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) { return false; }
    if (obj == this) { return true; }
    if (obj.getClass() != getClass()) {
      return false;
    }

    ACLEntry other = (ACLEntry) obj;
    return new EqualsBuilder()
        .append(itemPattern, other.itemPattern)
        .isEquals();
  }
}
