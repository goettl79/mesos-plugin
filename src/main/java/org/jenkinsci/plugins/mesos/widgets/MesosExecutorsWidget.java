package org.jenkinsci.plugins.mesos.widgets;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.widgets.Widget;
import jenkins.model.Jenkins;
import jenkins.widgets.ExecutorsWidget;
import org.jenkinsci.plugins.mesos.Mesos;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosSlave;

import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

@Extension(ordinal=100)
public class MesosExecutorsWidget extends Widget {

  private static final Logger LOGGER = Logger.getLogger(MesosExecutorsWidget.class.getName());

  @Initializer(fatal=false,after=InitMilestone.EXTENSIONS_AUGMENTED)
  @SuppressWarnings("unused")
  public static void removeDefaultExecutorsWidget() {
    Jenkins jenkins = Jenkins.getInstance();

    List<Widget> widgets = jenkins.getWidgets();
    for (Iterator<Widget> iterator = widgets.listIterator(); iterator.hasNext(); ) {
      Widget widget = iterator.next();
      if (widget instanceof ExecutorsWidget) {
        LOGGER.info("Removing default ExecutorsWidget");
        iterator.remove();
      }
    }
  }

}
