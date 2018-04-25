package org.jenkinsci.plugins.mesos.scheduling.creator;

import com.google.common.annotations.VisibleForTesting;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.mesos.Protos;
import org.jenkinsci.plugins.mesos.JenkinsScheduler;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.scheduling.Lease;
import org.jenkinsci.plugins.mesos.scheduling.Request;
import org.jenkinsci.plugins.mesos.scheduling.SlaveRequest;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TaskCreator {

    protected static final Logger LOGGER = Logger.getLogger(TaskCreator.class.getName());

    private static final String ITEM_FULLNAME_TOKEN  = "${ITEM_FULLNAME}";
    private static final String FRAMEWORK_NAME_TOKEN = "${FRAMEWORK_NAME}";
    private static final String JENKINS_MASTER_TOKEN = "${JENKINS_MASTER}";

    private static final String SLAVE_REQUEST_FORMAT="mesos/createSlave/%s";
    private static final String SLAVE_JAR_URI_SUFFIX = "jnlpJars/slave.jar";
    private static final String JNLP_SECRET_FORMAT = "-secret %s";
    private static final String SLAVE_COMMAND_FORMAT =
            "java -DHUDSON_HOME=jenkins -server -Xmx%dm %s -jar ${MESOS_SANDBOX-.}/slave.jar -noReconnect %s %s -jnlpUrl %s";


    private final Request request;
    private final Lease lease;
    private final JenkinsScheduler scheduler;

    // TODO: use lease only
    private final Protos.Offer offer;


    public TaskCreator(@Nonnull Request request, @Nonnull Lease lease, @Nonnull JenkinsScheduler scheduler) {
        this.request = request;
        this.lease = lease;
        this.scheduler = scheduler;

        // see
        this.offer = lease.getOffers().get(0);
    }

    public TaskCreator(@Nonnull Request request, @Nonnull Protos.Offer offer, @Nonnull JenkinsScheduler scheduler) {
        this(request, new Lease(offer), scheduler);
    }


    private String joinPaths(String prefix, String suffix) {
        if (prefix.endsWith("/"))   prefix = prefix.substring(0, prefix.length()-1);
        if (suffix.startsWith("/")) suffix = suffix.substring(1, suffix.length());

        return prefix + '/' + suffix;
    }

    private String replaceTokens(String text, Request request) {
        String result = StringUtils.replace(text, ITEM_FULLNAME_TOKEN, request.getRequest().getSlave().getLinkedItem());
        result = StringUtils.replace(result, FRAMEWORK_NAME_TOKEN, scheduler.getMesosCloud().getFrameworkName());
        result = StringUtils.replace(result, JENKINS_MASTER_TOKEN, scheduler.getJenkinsMaster());

        return result;
    }

    private String getJnlpUrl(String slaveName) {
        return joinPaths(joinPaths(joinPaths(scheduler.getJenkinsMaster(), "computer"), slaveName), "slave-agent.jnlp");
    }

    /**
     * Slave needs to go through authentication while connecting through jnlp if security is enabled in jenkins.
     * This method gets secret (for jnlp authentication) from jenkins, constructs command line argument and returns it.
     *
     * @param slaveName the slave name in jenkins
     * @return jenkins slave secret corresponding to slave name in the format '-secret <secret>'
     */
    private String getJnlpSecret(String slaveName) {
        String jnlpSecret = "";
        if(Jenkins.getInstance().isUseSecurity()) {
            jnlpSecret = String.format(JNLP_SECRET_FORMAT, jenkins.slaves.JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(slaveName));
        }
        return jnlpSecret;
    }


    public Protos.TaskInfo createTask() {
        final String agentName = request.getRequest().getSlave().getName();
        Protos.TaskID taskId = Protos.TaskID.newBuilder().setValue(agentName).build();

        LOGGER.fine("Creating task " + taskId.getValue() + " with URI " +
                joinPaths(scheduler.getJenkinsMaster(), SLAVE_JAR_URI_SUFFIX));

        // actual create task
        Protos.CommandInfo.Builder commandBuilder = getCommandInfoBuilder(request);
        Protos.TaskInfo.Builder taskBuilder = getTaskInfoBuilder(offer, request, taskId, commandBuilder);

        if (request.getRequest().getSlaveInfo().getContainerInfo() != null) {
            getContainerInfoBuilder(offer, request, agentName, taskBuilder);
        }

        return taskBuilder.build();
    }


    @VisibleForTesting
    Protos.CommandInfo.Builder getCommandInfoBuilder(Request request) {
        Protos.CommandInfo.Builder commandBuilder = getBaseCommandBuilder(request);
        detectAndAddAdditionalURIs(request, commandBuilder);
        return commandBuilder;
    }

    private void detectAndAddAdditionalURIs(Request request, Protos.CommandInfo.Builder commandBuilder) {
        MesosSlaveInfo slaveInfo = request.getRequest().getSlaveInfo();
        if (slaveInfo.getAdditionalURIs() != null) {
            for (MesosSlaveInfo.URI uri : slaveInfo.getAdditionalURIs()) {
                commandBuilder.addUris(
                        Protos.CommandInfo.URI.newBuilder().setValue(
                                uri.getValue()).setExecutable(uri.isExecutable()).setExtract(uri.isExtract()));
            }
        }
    }

    private Protos.CommandInfo.Builder getBaseCommandBuilder(Request request) {
        SlaveRequest slaveRequest = request.getRequest();
        MesosSlaveInfo slaveInfo = slaveRequest.getSlaveInfo();

        Protos.CommandInfo.Builder commandBuilder = Protos.CommandInfo.newBuilder();

        String command = StringUtils.EMPTY;
        String jenkinsMaster = scheduler.getJenkinsMaster();
        // make an "api call" to mesos so that Jenkins knows that he has to create a new slave on Jenkins instance.
        // user network (in our case) means an isolated network, so the fetcher will not be able to access jenkins
        String slaveRequestUri = joinPaths(jenkinsMaster, String.format(SLAVE_REQUEST_FORMAT, slaveRequest.getSlave().getName()));

        String slaveJarUri = joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX);

        Protos.ContainerInfo.DockerInfo.Network slaveNetwork = Protos.ContainerInfo.DockerInfo.Network.NONE;
        if (slaveInfo.getContainerInfo() != null) {
            slaveNetwork = Protos.ContainerInfo.DockerInfo.Network.valueOf(slaveInfo.getContainerInfo().getNetworking());
        }

        if (Protos.ContainerInfo.DockerInfo.Network.USER.equals(slaveNetwork)) {
            String requestSlaveCommand = "curl -o ${MESOS_SANDBOX}/" + slaveRequest.getSlave().getName() + " " + slaveRequestUri;
            String downloadSlaveJarCommand = "curl -o ${MESOS_SANDBOX}/slave.jar " + slaveJarUri;

            command = requestSlaveCommand + " && " + downloadSlaveJarCommand + " && ";
        } else {
            commandBuilder.addUris(
                    Protos.CommandInfo.URI.newBuilder().setValue(slaveRequestUri).setExecutable(false).setExtract(false));
            commandBuilder.addUris(
                    Protos.CommandInfo.URI.newBuilder().setValue(slaveJarUri).setExecutable(false).setExtract(false));
        }

        command += generateJenkinsCommand2Run(request);

        if (slaveInfo.getContainerInfo() != null &&
                slaveInfo.getContainerInfo().getUseCustomDockerCommandShell()) {
            // Ref http://mesos.apache.org/documentation/latest/upgrades
            // regarding setting the shell value, and the impact on the command to be
            // launched
            String customShell = slaveInfo.getContainerInfo().getCustomDockerCommandShell();
            if (StringUtils.stripToNull(customShell) == null) {
                throw new IllegalArgumentException("Invalid custom shell argument supplied");
            }

            LOGGER.info(String.format("About to use custom shell: %s ", customShell));
            commandBuilder.setShell(false);
            commandBuilder.setValue(customShell);
            List args = new ArrayList();
            args.add(command);
            commandBuilder.addAllArguments(args);

        } else {
            LOGGER.info("About to use default shell ....");
            commandBuilder.setValue(command);
        }

        return commandBuilder;
    }

    @VisibleForTesting
    String generateJenkinsCommand2Run(Request request) {
        SlaveRequest slaveRequest = request.getRequest();
        MesosSlaveInfo slaveInfo = slaveRequest.getSlaveInfo();

        int jvmMem = slaveInfo.getSlaveMem();
        String jvmArgString = slaveInfo.getJvmArgs();
        String jnlpArgString = slaveInfo.getJnlpArgs();
        String slaveName = slaveRequest.getSlave().getName();
        MesosSlaveInfo.RunAsUserInfo runAsUserInfo = slaveInfo.getRunAsUserInfo();
        List<MesosSlaveInfo.Command> additionalCommands = slaveInfo.getAdditionalCommands();

        String slaveCmd = String.format(SLAVE_COMMAND_FORMAT,
                jvmMem,
                jvmArgString,
                jnlpArgString,
                getJnlpSecret(slaveName),
                getJnlpUrl(slaveName));

        if (runAsUserInfo != null) {
            slaveCmd = runAsUserInfo.getCommand()
                    .replace(MesosSlaveInfo.RunAsUserInfo.TOKEN_USERNAME, runAsUserInfo.getUsername())
                    .replace(MesosSlaveInfo.RunAsUserInfo.TOKEN_SLAVE_COMMAND, slaveCmd);
        }

        StringBuilder commandStringBuilder = new StringBuilder();

        if (additionalCommands != null && !additionalCommands.isEmpty()) {
            for (MesosSlaveInfo.Command additionalCommand : additionalCommands) {
                commandStringBuilder.append(replaceTokens(additionalCommand.getValue(), request)).append(" && ");
            }
            commandStringBuilder.append("exec ");
            commandStringBuilder.append(slaveCmd);
            return commandStringBuilder.toString();
        }

        return slaveCmd;
    }

    private Protos.TaskInfo.Builder getTaskInfoBuilder(Protos.Offer offer, Request request, Protos.TaskID taskId, Protos.CommandInfo.Builder commandBuilder) {
        Protos.TaskInfo.Builder builder = Protos.TaskInfo.newBuilder()
                .setName("task " + taskId.getValue())
                .setTaskId(taskId)
                .setSlaveId(offer.getSlaveId())
                .setCommand(commandBuilder.build());

        SlaveRequest slaveRequest = request.getRequest();
        double cpusNeeded = slaveRequest.getSlave().getCpus();
        double memNeeded = slaveRequest.getSlave().getMem();

        // TODO: prettify "for (Protos.Resource... )
        /*Double cpusToAssign = request.getCpus();
        Iterator<String> iterator = request.getRoles().iterator();
        while (cpusToAssign > 0.0 && iterator.hasNext()) {
            String role = iterator.next();

            // assign cpus
            Double availableCpus = getAvailableCpus(role);
            Double assignableCpus = Math.min(cpusToAssign, availableCpus);

            if (assignableCpus > 0.0) {
                task.addResource(CPUS_NAME, assignableCpus, role);
                cpusToAssign -= assignableCpus;
                substractAvailableCpus(role, assignableCpus);
            }
        }

        Double memToAssign = request.getMem();
        iterator = request.getRoles().iterator();
        while (memToAssign > 0.0 && iterator.hasNext()) {
            String role = iterator.next();

            // assign cpus
            Double availableMem = getAvailableMem(role);
            Double assignableMem = Math.min(memToAssign, availableMem);

            if (assignableMem > 0.0) {
                task.addResource(MEM_NAME, assignableMem, role);
                memToAssign -= assignableMem;
                substractAvailableMem(role, assignableMem);
            }
        }*/

        for (Protos.Resource r : offer.getResourcesList()) {
            if (r.getName().equals("cpus") && cpusNeeded > 0) {
                double cpus = Math.min(r.getScalar().getValue(), cpusNeeded);
                builder.addResources(
                        Protos.Resource
                                .newBuilder()
                                .setName("cpus")
                                .setType(Protos.Value.Type.SCALAR)
                                .setRole(r.getRole())
                                .setScalar(
                                        Protos.Value.Scalar.newBuilder()
                                                .setValue(cpus).build()).build());
                cpusNeeded -= cpus;
            } else if (r.getName().equals("mem") && memNeeded > 0.0) {
                double mem = Math.min(r.getScalar().getValue(), memNeeded);
                builder.addResources(
                        Protos.Resource
                                .newBuilder()
                                .setName("mem")
                                .setType(Protos.Value.Type.SCALAR)
                                .setRole(r.getRole())
                                .setScalar(
                                        Protos.Value.Scalar
                                                .newBuilder()
                                                .setValue(mem)
                                                .build()).build());
                memNeeded -= mem;
            } else if (cpusNeeded <= 0.0 && memNeeded <= 0.0) {
                break;
            }
        }

        return builder;
    }

    private void getContainerInfoBuilder(Protos.Offer offer, Request request, String slaveName, Protos.TaskInfo.Builder taskBuilder) {
        MesosSlaveInfo slaveInfo = request.getRequest().getSlaveInfo();

        MesosSlaveInfo.ContainerInfo containerInfo = slaveInfo.getContainerInfo();
        Protos.ContainerInfo.Type containerType = Protos.ContainerInfo.Type.valueOf(containerInfo.getType());

        Protos.ContainerInfo.Builder containerInfoBuilder = Protos.ContainerInfo.newBuilder() //
                .setType(containerType); //

        switch(containerType) {
            case DOCKER:
                LOGGER.info("Launching in Docker Mode:" + containerInfo.getDockerImage());
                Protos.ContainerInfo.DockerInfo.Builder dockerInfoBuilder = Protos.ContainerInfo.DockerInfo.newBuilder() //
                        .setImage(containerInfo.getDockerImage())
                        .setPrivileged(containerInfo.getDockerPrivilegedMode() != null ? containerInfo.getDockerPrivilegedMode() : false)
                        .setForcePullImage(containerInfo.getDockerForcePullImage() != null ? containerInfo.getDockerForcePullImage() : false);

                if (containerInfo.getParameters() != null) {
                    for (MesosSlaveInfo.Parameter parameter : containerInfo.getParameters()) {
                        LOGGER.info("Adding Docker parameter '" + parameter.getKey() + ":" + parameter.getValue() + "'");
                        dockerInfoBuilder.addParameters(Protos.Parameter.newBuilder().setKey(parameter.getKey()).setValue(parameter.getValue()).build());
                    }
                }

                String networking = slaveInfo.getContainerInfo().getNetworking();
                Protos.ContainerInfo.DockerInfo.Network dockerNetwork = Protos.ContainerInfo.DockerInfo.Network.valueOf(networking);

                dockerInfoBuilder.setNetwork(dockerNetwork);

                //  https://github.com/jenkinsci/mesos-plugin/issues/109
                if (!Protos.ContainerInfo.DockerInfo.Network.HOST.equals(dockerNetwork)) {
                    containerInfoBuilder.setHostname(slaveName);
                }

                if (Protos.ContainerInfo.DockerInfo.Network.USER.equals(dockerNetwork)) {
                    MesosCloud mesosCloud = scheduler.getMesosCloud();

                    /*
                     * create network name out of principal and framework name to be relatively secure
                     * because, the password of the principal should only be known to the admins who configure the network
                     * thus, other containers of other frameworks cannot use this network in theory
                     *
                     * TODO: let choose between auto-generated and configured
                     */
                    final String networkName = String.format("%s-%s", //
                            mesosCloud.getPrincipal(), //
                            StringUtils.replace(mesosCloud.getFrameworkName(), " ", "-"));

                    LOGGER.log(Level.FINER, "Setting the USER network name to '" + networkName + "'");

                    Protos.NetworkInfo.Builder networkInfoBuilder = Protos.NetworkInfo.newBuilder().setName(networkName);
                    containerInfoBuilder.addNetworkInfos(networkInfoBuilder);
                }

                if (slaveInfo.getContainerInfo().hasPortMappings()) {
                    Set<MesosSlaveInfo.PortMapping> portMappings = slaveInfo.getContainerInfo().getPortMappings();
                    Set<Long> portsToUse = findPortsToUse(offer, portMappings.size());
                    Iterator<Long> iterator = portsToUse.iterator();
                    Protos.Value.Ranges.Builder portRangesBuilder = Protos.Value.Ranges.newBuilder();

                    for (MesosSlaveInfo.PortMapping portMapping : portMappings) {
                        Protos.ContainerInfo.DockerInfo.PortMapping.Builder portMappingBuilder = Protos.ContainerInfo.DockerInfo.PortMapping.newBuilder() //
                                .setContainerPort(portMapping.getContainerPort()) //
                                .setProtocol(portMapping.getProtocol());

                        Integer portToUse = portMapping.isStaticHostPort() ? portMapping.getHostPort() : iterator.next().intValue();

                        portMappingBuilder.setHostPort(portToUse);

                        portRangesBuilder.addRange(
                                Protos.Value.Range
                                        .newBuilder()
                                        .setBegin(portToUse)
                                        .setEnd(portToUse)
                        );

                        LOGGER.finest("Adding portMapping: " + portMapping);
                        dockerInfoBuilder.addPortMappings(portMappingBuilder);
                    }

                    taskBuilder.addResources(
                            Protos.Resource
                                    .newBuilder()
                                    .setName("ports")
                                    .setType(Protos.Value.Type.RANGES)
                                    .setRanges(portRangesBuilder)
                    );
                } else {
                    LOGGER.fine("No portMappings found");
                }

                containerInfoBuilder.setDocker(dockerInfoBuilder);
                break;
            default:
                LOGGER.warning("Unknown container type:" + containerInfo.getType());
        }

        if (containerInfo.getVolumes() != null) {
            for (MesosSlaveInfo.Volume volume : containerInfo.getVolumes()) {
                LOGGER.info("Adding volume '" + volume.getContainerPath() + "'");
                Protos.Volume.Builder volumeBuilder = Protos.Volume.newBuilder()
                        .setContainerPath(replaceTokens(volume.getContainerPath(), request))
                        .setMode(volume.isReadOnly() ? Protos.Volume.Mode.RO : Protos.Volume.Mode.RW);

                String hostPath = volume.getHostPath();
                if (!StringUtils.isBlank(hostPath)) {
                    volumeBuilder.setHostPath(replaceTokens(hostPath, request));
                }

                containerInfoBuilder.addVolumes(volumeBuilder.build());
            }
        }

        taskBuilder.setContainer(containerInfoBuilder.build());
    }

    @VisibleForTesting
    SortedSet<Long> findPortsToUse(Protos.Offer offer, int maxCount) {
        SortedSet<Long> portsToUse = new TreeSet<Long>();
        List<Protos.Value.Range> portRangesList = null;

        // Locate the port resource in the offer
        for (Protos.Resource resource : offer.getResourcesList()) {
            if (resource.getName().equals("ports")) {
                portRangesList = resource.getRanges().getRangeList();
                break;
            }
        }

        if (portRangesList != null) {
            LOGGER.fine("portRangesList=" + portRangesList);

            /**
             * We need to find maxCount ports to use.
             * We are provided a list of port ranges to use
             * We are assured by the offer check that we have enough ports to use
             */
            // Check this port range for ports that we can use
            for (Protos.Value.Range currentPortRange : portRangesList) {
                // Check each port until we reach the end of the current range
                long begin = currentPortRange.getBegin();
                long end = currentPortRange.getEnd();
                for (long candidatePort = begin; candidatePort <= end && portsToUse.size() < maxCount; candidatePort++) {
                    portsToUse.add(candidatePort);
                }
            }
        } else {
            LOGGER.fine("Unable to determine ports to use, because no port resources were found in " + offer);
        }

        return portsToUse;
    }
}
