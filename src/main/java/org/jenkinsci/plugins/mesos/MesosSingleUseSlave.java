package org.jenkinsci.plugins.mesos;


import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.slaves.OfflineCause;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.logging.Logger;

/**
 This file is part of the JCloud Jenkins Plugin. (https://github.com/jenkinsci/jclouds-plugin)
 commit id 20c6ca884abb172b27d0af82545c8915ce08f618.

 This file was modified to work with the Mesos Jenkins plugin and based off the JClouds Jenkins Plugin.

 According to the Jenkins Plugin guide (https://wiki.jenkins-ci.org/display/JENKINS/Before+starting+a+new+plugin),
 If no license is defined in the form of a header, LICENSE file, or in the license section, the code is assumed to be
 under The MIT License.

 The MIT License (MIT)

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 */
public class MesosSingleUseSlave extends BuildWrapper {
    private static final Logger LOGGER = Logger.getLogger(MesosSingleUseSlave.class.getName());

    @DataBoundConstructor
    public MesosSingleUseSlave() {
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Environment setUp(AbstractBuild build, Launcher launcher, final BuildListener listener) {
        final Executor executor = build.getExecutor();
        if (executor == null) {
            LOGGER.warning("Unable to determine executor for build '" + build + "' (was null)");
            return null;
        }

        Computer computer = executor.getOwner();
        if (computer instanceof MesosComputer) {
            final MesosComputer c = (MesosComputer) computer;
            return new Environment() {
                @Override
                public boolean tearDown(AbstractBuild build, final BuildListener listener) {
                    LOGGER.warning("Single-use slave " + c.getName() + " getting torn down.");
                    c.setTemporarilyOffline(true, OfflineCause.create(Messages._singleUseCause()));
                    return true;
                }
            };
        } else {
            return new Environment() {
                @Override
                public boolean tearDown(AbstractBuild build, final BuildListener listener) {
                    LOGGER.fine("Not a single use slave, this is a " + executor.getOwner().getClass());
                    return true;
                }
            };
        }

    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        @Override
        public String getDisplayName() {
            return "Mesos Single-Use Slave";
        }

        @Override
        @SuppressWarnings("rawtypes")
        public boolean isApplicable(AbstractProject item) {
            return false;
        }

    }
}

