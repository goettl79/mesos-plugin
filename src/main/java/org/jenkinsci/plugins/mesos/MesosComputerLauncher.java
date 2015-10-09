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

import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.util.logging.Logger;

public class MesosComputerLauncher extends ComputerLauncher {

  private final MesosCloud cloud;

  enum State { INIT, RUNNING, FAILURE }

  private static final Logger LOGGER = Logger.getLogger(MesosComputerLauncher.class.getName());

  public MesosComputerLauncher(MesosCloud cloud, String _name) {
    super();
    LOGGER.info("Constructing MesosComputerLauncher");
    this.cloud = cloud;
    this.state = State.INIT;
    this.name = _name;
  }

  /**
   * Launches a mesos task that starts the jenkins slave.
   *
   * NOTE: This has to be a blocking call:
   *
   * @see hudson.slaves.ComputerLauncher#launch(hudson.slaves.SlaveComputer,
   *      hudson.model.TaskListener)
   */
  @Override
  public void launch(SlaveComputer _computer, TaskListener listener) throws InterruptedException {

  }

  /**
   * Kills the mesos task that corresponds to the Jenkins slave, asynchronously.
   */
  public void terminate() {
    // Get a handle to mesos.
    Mesos mesos = Mesos.getInstance(cloud);

    mesos.stopJenkinsSlave(name);
  }

  private volatile State state;
  private final String name;
}
