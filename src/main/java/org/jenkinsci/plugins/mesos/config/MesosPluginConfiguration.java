package org.jenkinsci.plugins.mesos.config;

import hudson.BulkChange;
import hudson.Extension;
import hudson.model.Api;
import hudson.model.Descriptor;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import hudson.util.FormApply;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.Messages;
import org.jenkinsci.plugins.mesos.config.acl.MesosFrameworkToItemMapper;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.SlaveDefinitionsConfiguration;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
@ExportedBean
public class MesosPluginConfiguration extends ManagementLink {

  private static final Logger LOGGER = Logger.getLogger(MesosPluginConfiguration.class.getName());

  public static final String PLUGIN_URL_NAME = "mesos";
  public static final String CONFIG_URL_NAME = "configureMesosPlugin";
  public static final String ICON_FILE_NAME = "/plugin/" + PLUGIN_URL_NAME + "/images/48x48/mesos-logo.png";

  private final MesosFrameworkToItemMapper mapper = new MesosFrameworkToItemMapper();
  private final SlaveDefinitionsConfiguration slaveDefinitionsConfiguration = new SlaveDefinitionsConfiguration();

  @Exported(inline = true, visibility = 1)
  public MesosFrameworkToItemMapper getMapper() {
    return mapper;
  }

  @Exported(inline = true, visibility = 1)
  public SlaveDefinitionsConfiguration getSlaveDefinitionsConfiguration() {
    return slaveDefinitionsConfiguration;
  }

  @RequirePOST
  public synchronized void doConfigureMappings(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, Descriptor.FormException {
    BulkChange bc = new BulkChange(Jenkins.get());
    try{
      boolean result = getMapper().getDescriptor().configure(req, req.getSubmittedForm());
      LOGGER.log(Level.FINER, "Mappings saved: " + result);
      FormApply.success(req.getContextPath() + "/" + CONFIG_URL_NAME).generateResponse(req, rsp, null);
    } finally {
      bc.commit();
    }
  }

  @RequirePOST
  public synchronized void doConfigureDefinitions(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, Descriptor.FormException {
    BulkChange bc = new BulkChange(Jenkins.get());
    try{
      boolean result = getSlaveDefinitionsConfiguration().getDescriptor().configure(req, req.getSubmittedForm());
      LOGGER.log(Level.FINER, "Definitions saved: " + result);
      FormApply.success(req.getContextPath() + "/" + CONFIG_URL_NAME).generateResponse(req, rsp, null);
    } finally {
      bc.commit();
    }
  }

  @Override
  public Permission getRequiredPermission() {
    return Jenkins.ADMINISTER;
  }

  @Override
  public String getIconFileName() {
    return ICON_FILE_NAME;
  }

  @Override
  public String getDisplayName() {
    return Messages.MesosPluginConfiguration_DisplayName();
  }

  @Override
  public String getUrlName() {
    return CONFIG_URL_NAME;
  }

  public Api getApi() {
    return new Api(this);
  }

}
