/*
 * Copyright 2013 Twitter, Inc. and other contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.apache.mesos.Scheduler;
import org.jenkinsci.plugins.mesos.scheduling.SlaveRequest;
import org.jenkinsci.plugins.mesos.scheduling.SlaveResult;

import java.util.*;

public abstract class Mesos {
  private static Map<MesosCloud, Mesos> clouds = new HashMap<>();


  abstract public void startScheduler(String jenkinsMaster, MesosCloud mesosCloud);
  abstract public void updateScheduler(String jenkinsMaster, MesosCloud mesosCloud);
  abstract public boolean isSchedulerRunning();
  abstract public void stopScheduler();
  abstract public Scheduler getScheduler();
  /**
   * Starts a jenkins slave asynchronously in the mesos cluster.
   *
   * @param request
   *          slave request.
   * @param result
   *          this callback will be called when the slave starts.
   */
  abstract public void startJenkinsSlave(SlaveRequest request, SlaveResult result);


  /**
   * Stop a jenkins slave asynchronously in the mesos cluster.
   *
   * @param name
   *          jenkins slave.
   *
   */
  abstract public void stopJenkinsSlave(String name);

  /**
   * Retrieves a Mesos cloud instance.
   *
   * @param key name of the mesos cloud/framework
   * @return the mesos implementation instance for the cloud instances (since there might be more than one
   */
  public static synchronized Mesos getInstance(MesosCloud key) {
    if (!clouds.containsKey(key)) {
      clouds.put(key, new MesosImpl());
    }
    return clouds.get(key);
  }

  public static Collection<Mesos> getAllClouds() {
    return clouds.values();
  }

  public static Collection<MesosCloud> getAllMesosClouds() {
    List<MesosCloud> mesosClouds = new ArrayList<>();

    Jenkins jenkins = Jenkins.get();
    for (Cloud cloud : jenkins.clouds) {
      if (cloud instanceof MesosCloud) {
        mesosClouds.add((MesosCloud) cloud);
      }
    }

    return Collections.unmodifiableCollection(mesosClouds);
  }

  /**
   * When Jenkins configuration is saved, teardown any active scheduler whose cloud has been removed.
   */
  @Extension
  public static class GarbageCollectorImpl extends SaveableListener {

    @Override
    public void onChange(Saveable o, XmlFile file) {
      if (o instanceof Jenkins) {
        Jenkins j = (Jenkins) o;
        for (Iterator<Map.Entry<MesosCloud, Mesos>> it = clouds.entrySet().iterator(); it.hasNext();) {
          Map.Entry<MesosCloud, Mesos> entry = it.next();
          if (!j.clouds.contains(entry.getKey())) {
            entry.getValue().stopScheduler();
            it.remove();
          }
        }
      }
    }
  }
}
