package org.jenkinsci.plugins.mesos.config.slavedefinitions;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.util.Collections;
import java.util.List;

@ExportedBean
public class MesosSlaveDefinitions {

  private final String definitionsName;
  private final List<MesosSlaveInfo> mesosSlaveInfos;

  @DataBoundConstructor
  public MesosSlaveDefinitions(String definitionsName, List<MesosSlaveInfo> mesosSlaveInfos) {
    this.definitionsName = definitionsName;
    this.mesosSlaveInfos = mesosSlaveInfos;
  }

  /*package*/ MesosSlaveDefinitions(String definitionsName, MesosSlaveDefinitions oldSlaveDefinitionsEntry) {
    this(definitionsName, oldSlaveDefinitionsEntry.mesosSlaveInfos);
  }


  @Exported
  public String getDefinitionsName() {
    return definitionsName;
  }

  @Exported(inline = true, visibility = 1)
  public List<MesosSlaveInfo> getMesosSlaveInfos() {
    if (mesosSlaveInfos == null) {
      return null;
    } else {
      return Collections.unmodifiableList(mesosSlaveInfos);
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) { return false; }
    if (obj == this) { return true; }
    if (obj.getClass() != getClass()) {
      return false;
    }

    MesosSlaveDefinitions other = (MesosSlaveDefinitions) obj;
    return new EqualsBuilder()
        .append(definitionsName, other.definitionsName)
        .isEquals();
  }

  @Override
  public String toString() {
    return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
        .append("definitionsName", definitionsName)
        .append("mesosSlaveInfosSize", mesosSlaveInfos == null ? null : mesosSlaveInfos.size())
        .toString();
  }

}
