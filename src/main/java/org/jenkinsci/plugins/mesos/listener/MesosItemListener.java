package org.jenkinsci.plugins.mesos.listener;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.listeners.ItemListener;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.mesos.Mesos;
import org.jenkinsci.plugins.mesos.MesosCloud;

import java.util.logging.Level;
import java.util.logging.Logger;


@Extension
public class MesosItemListener extends ItemListener {
    private static Logger LOGGER = Logger.getLogger(MesosItemListener.class.getName());

    @Override
    public void onCreated(final Item item) {
        setDefaultLabel(item);
    }

    @Override
    public void onUpdated(Item item) {
        setDefaultLabel(item);
    }

    /**
     * Sets a (configured) default label for a viable item if item does not have a label configured
     *
     * @param item The item for which to set the default label
     */
    private void setDefaultLabel(Item item) {
        if (item == null || !(item instanceof AbstractProject)) {
            LOGGER.fine("Not able to set a default label for item ('" + item + "')");
            return;
        }

        AbstractProject job = (AbstractProject) item;
        Label label = job.getAssignedLabel();
        try {
            if (label == null) { // No label assigned, override now
                String fullName = item.getFullName();
                LOGGER.fine("Item '" + fullName + "' has no label assigned");

                label = getDefaultLabel(fullName);

                if (label != null) {
                    LOGGER.fine("Assigning '" + label.getName() + "' to item '" + fullName + "'");
                    job.setAssignedLabel(label);
                } else {
                    LOGGER.fine("Was not able to assign label to item '" + fullName + "'");
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to assign label '" + label + "' to item '" + item.getFullName() + "'", e);
        }
    }


    /**
     * Get the associated/mapped Mesos Cloud/Framework for the specified full name of the item
     *
     * @param fullName Full name of the item for which to get the associated/mapped MesosCloud
     * @return either the mapped MesosCloud for the item or <code>null</code> if none could be found
     */
    private MesosCloud getMesosCloud(String fullName) {
        for (MesosCloud mesosCloud : Mesos.getAllMesosClouds()) {
            if (mesosCloud.isItemForMyFramework(fullName)) {
                return mesosCloud;
            }
        }
        return null;
    }

    /**
     * Get the default label for the specified full name of the item
     *
     * @param fullName Full name of the item for which to get the default label
     * @return either the found label or <code>null</code> when no label has been found
     */
    private Label getDefaultLabel(String fullName) {
        Label label = null;

        if (StringUtils.isBlank(fullName)) {
            LOGGER.warning("Full name of item is empty or null");
            return label;
        }

        // get mesos cloud
        final MesosCloud cloud = getMesosCloud(fullName);
        if (cloud != null) {
            String labelName = cloud.getDefaultSlaveLabel();
            if (!StringUtils.equals(MesosCloud.DEFAULT_SLAVE_LABEL_NONE, labelName)) {
                // TODO: check if label really exists
                label = Jenkins.getInstance().getLabel(cloud.getDefaultSlaveLabel());
            } else {
                LOGGER.warning("No default label specified for cloud '" + cloud.getFrameworkName() + "'");
            }
        } else {
            LOGGER.warning("No cloud was found, cannot set label for item '" + fullName + "'");
        }

        return label;
    }

}
