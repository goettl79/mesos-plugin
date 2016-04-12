package org.jenkinsci.plugins.mesos.config.slavedefinitions;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.util.Collections;
import java.util.List;

@ExportedBean
public class MesosSlaveDefinitions {

  private final String definitionsName;
  private final List<MesosSlaveInfo> mesosSlaveInfos;

  public MesosSlaveDefinitions() {
    this("Default Slave Definitions", Collections.<MesosSlaveInfo>emptyList());
  }

  @DataBoundConstructor
  public MesosSlaveDefinitions(String definitionsName, List<MesosSlaveInfo> mesosSlaveInfos) {
    this.definitionsName = definitionsName;
    this.mesosSlaveInfos = mesosSlaveInfos;
  }


  @Exported
  public String getDefinitionsName() {
    return definitionsName;
  }

  @Exported(inline = true, visibility = 1)
  public List<MesosSlaveInfo> getMesosSlaveInfos() {
    return Collections.unmodifiableList(mesosSlaveInfos);
  }
}
