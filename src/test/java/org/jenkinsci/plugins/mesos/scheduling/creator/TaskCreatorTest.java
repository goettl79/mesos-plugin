package org.jenkinsci.plugins.mesos.scheduling.creator;


import hudson.model.Node;
import jenkins.model.Jenkins;
import org.apache.mesos.Protos;
import org.jenkinsci.plugins.mesos.JenkinsScheduler;
import org.jenkinsci.plugins.mesos.JenkinsSchedulerOld;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.scheduling.JenkinsSlave;
import org.jenkinsci.plugins.mesos.scheduling.Request;
import org.jenkinsci.plugins.mesos.scheduling.SlaveRequest;
import org.jenkinsci.plugins.mesos.scheduling.SlaveResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Collections;
import java.util.SortedSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest( { Jenkins.class })
public class TaskCreatorTest {
    @Mock
    private MesosCloud mesosCloud;

    private TaskCreator taskCreator;

    private static double TEST_JENKINS_SLAVE_MEM   = 512;
    private static String TEST_JENKINS_SLAVE_ARG   = "-Xms16m -XX:+UseConcMarkSweepGC -Djava.net.preferIPv4Stack=true";
    private static String TEST_JENKINS_JNLP_ARG    = "";
    private static String TEST_JENKINS_SLAVE_NAME  = "testSlave1";


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mesosCloud.getMaster()).thenReturn("Mesos Cloud Master");

        // Simulate basic Jenkins env
        Jenkins jenkins = Mockito.mock(Jenkins.class);
        when(jenkins.isUseSecurity()).thenReturn(false);
        PowerMockito.mockStatic(Jenkins.class);
        Mockito.when(Jenkins.getInstance()).thenReturn(jenkins);
    }

    @Test
    public void testFindPortsToUse() {
        JenkinsScheduler scheduler = new JenkinsSchedulerOld("jenkinsMaster", mesosCloud);
        Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        Request request = mockMesosRequest(Boolean.FALSE, null, null);
        taskCreator = new TaskCreator(request, offer, scheduler);

        SortedSet<Long> portsToUse = taskCreator.findPortsToUse(offer, 1);

        assertEquals(1, portsToUse.size());
        assertEquals(Long.valueOf(31000), portsToUse.first());
    }

    @Test
    public void testFindPortsToUseSamePortNumber() {
        JenkinsScheduler scheduler = new JenkinsSchedulerOld("jenkinsMaster", mesosCloud);
        final Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        Request request = mockMesosRequest(Boolean.FALSE, null, null);
        taskCreator = new TaskCreator(request, offer, scheduler);

        final ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            SortedSet<Long> portsToUse = taskCreator.findPortsToUse(offer, 1);

            assertEquals(1, portsToUse.size());
            assertEquals(Long.valueOf(31000), portsToUse.first());
        });

        executorService.shutdown();

        // Test that there is no infinite loop by asserting that the task finishes in 500ms or less
        try {
            assertTrue(executorService.awaitTermination(500L, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    public void testSingleFirstRangeLongRangeAfterNoInfiniteLoop() {
        Protos.Value.Range range = Protos.Value.Range.newBuilder()
                .setBegin(31000)
                .setEnd(31000)
                .build();

        Protos.Value.Range range2 = Protos.Value.Range.newBuilder()
                .setBegin(31005)
                .setEnd(32000)
                .build();

        Protos.Value.Ranges ranges = Protos.Value.Ranges.newBuilder()
                .addRange(range)
                .addRange(range2)
                .build();

        Protos.Resource resource = Protos.Resource.newBuilder()
                .setName("ports")
                .setRanges(ranges)
                .setType(Protos.Value.Type.RANGES)
                .build();

        final Protos.Offer protoOffer = Protos.Offer.newBuilder()
                .addResources(resource)
                .setId(Protos.OfferID.newBuilder().setValue("value").build())
                .setFrameworkId(Protos.FrameworkID.newBuilder().setValue("value").build())
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("value").build())
                .setHostname("hostname")
                .build();

        JenkinsScheduler scheduler = new JenkinsSchedulerOld("jenkinsMaster", mesosCloud);
        Request request = mockMesosRequest(Boolean.FALSE, null, null);
        taskCreator = new TaskCreator(request, protoOffer, scheduler);

        final ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            SortedSet<Long> portsToUse = taskCreator.findPortsToUse(protoOffer, 2);

            assertEquals(2, portsToUse.size());
            assertEquals(Long.valueOf(31000), portsToUse.first());
            assertEquals(Long.valueOf(31005), portsToUse.last());
        });

        executorService.shutdown();

        // Test that there is no infinite loop by asserting that the task finishes in 500ms or less
        try {
            assertTrue(executorService.awaitTermination(999999500L, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    public void testConstructMesosCommandInfoWithNoContainer() throws Exception {
        JenkinsScheduler scheduler = new JenkinsSchedulerOld("jenkinsMaster", mesosCloud);
        final Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        Request request = mockMesosRequest(Boolean.FALSE, null, null);
        taskCreator = new TaskCreator(request, offer, scheduler);


        Protos.CommandInfo.Builder commandInfoBuilder = taskCreator.getCommandInfoBuilder(request);
        Protos.CommandInfo commandInfo = commandInfoBuilder.build();

        assertTrue("Default shell config (true) should be configured when no container specified", commandInfo.getShell());

        String jenkinsCommand2Run = taskCreator.generateJenkinsCommand2Run(request);
        assertEquals("jenkins command to run should be specified as value", jenkinsCommand2Run, commandInfo.getValue());
        assertEquals("mesos command should have no args specified by default", 0, commandInfo.getArgumentsCount());
    }

    @Test
    public void testConstructMesosCommandInfoWithDefaultDockerShell() throws Exception {
        JenkinsScheduler scheduler = new JenkinsSchedulerOld("jenkinsMaster", mesosCloud);
        final Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        Request request = mockMesosRequest(Boolean.TRUE,false,null);
        taskCreator = new TaskCreator(request, offer, scheduler);

        Protos.CommandInfo.Builder commandInfoBuilder = taskCreator.getCommandInfoBuilder(request);
        Protos.CommandInfo commandInfo = commandInfoBuilder.build();

        assertTrue("Default shell config (true) should be configured when no container specified", commandInfo.getShell());
        String jenkinsCommand2Run = taskCreator.generateJenkinsCommand2Run(request);
        assertEquals("jenkins command to run should be specified as value", jenkinsCommand2Run, commandInfo.getValue());
        assertEquals("mesos command should have no args specified by default", 0, commandInfo.getArgumentsCount());
    }

    @Test
    public void testConstructMesosCommandInfoWithCustomDockerShell() throws Exception {
        JenkinsScheduler scheduler = new JenkinsSchedulerOld("jenkinsMaster", mesosCloud);
        final Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        Request request = mockMesosRequest(Boolean.TRUE, true, "/bin/wrapdocker");
        taskCreator = new TaskCreator(request, offer, scheduler);


        Protos.CommandInfo.Builder commandInfoBuilder = taskCreator.getCommandInfoBuilder(request);
        Protos.CommandInfo commandInfo = commandInfoBuilder.build();

        assertFalse("shell should be configured as false when using a custom shell", commandInfo.getShell());
        assertEquals("Custom shell should be specified as value", "/bin/wrapdocker", commandInfo.getValue());
        String jenkinsCommand2Run = taskCreator.generateJenkinsCommand2Run(request);

        assertEquals("args should now consist of the single original command ", 1, commandInfo.getArgumentsCount());
        assertEquals("args should now consist of the original command ", jenkinsCommand2Run, commandInfo.getArguments(0));
    }

    @Test(expected = java.lang.IllegalArgumentException.class)
    public void testConstructMesosCommandInfoWithBlankCustomDockerShell() throws Exception {
        Request request = mockMesosRequest(Boolean.TRUE, true, " ");
        JenkinsScheduler scheduler = new JenkinsSchedulerOld("jenkinsMaster", mesosCloud);
        final Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        taskCreator = new TaskCreator(request, offer, scheduler);

        taskCreator.getCommandInfoBuilder(request);
    }

    @Test(expected = java.lang.IllegalArgumentException.class)
    public void testConstructMesosCommandInfoWithNullCustomDockerShell() throws Exception {
        Request request = mockMesosRequest(Boolean.TRUE, true, null);
        JenkinsScheduler scheduler = new JenkinsSchedulerOld("jenkinsMaster", mesosCloud);
        final Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        taskCreator = new TaskCreator(request, offer, scheduler);

        taskCreator.getCommandInfoBuilder(request);
    }

    private Request mockMesosRequest(
            Boolean useDocker,
            Boolean useCustomDockerCommandShell,
            String customDockerCommandShell) {

        MesosSlaveInfo.ContainerInfo containerInfo = null;
        if (useDocker) {
            containerInfo = new MesosSlaveInfo.ContainerInfo(
                    "docker",
                    "test-docker-in-docker-image",
                    Boolean.TRUE,
                    Boolean.TRUE,
                    useCustomDockerCommandShell,
                    customDockerCommandShell,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Protos.ContainerInfo.DockerInfo.Network.HOST.name(),
                    Collections.emptyList());
        }

        MesosSlaveInfo mesosSlaveInfo = new MesosSlaveInfo(
                "testLabelString",  // labelString,
                Node.Mode.NORMAL,
                "0.2",              // slaveCpus,
                "512",              //slaveMem,
                "2",                // maxExecutors,
                "0.2",              // executorCpus,
                "512",              // executorMem,
                "remoteFSRoot",     // remoteFSRoot,
                "2",                // idleTerminationMinutes,
                "2",                // maxTtl,
                false,
                "",               // slaveAttributes,
                null,               // jvmArgs,
                null,               //jnlpArgs,
                containerInfo,      // containerInfo,
                null,               //additionalURIs
                null,                // runAsUserInfo
                null            // additionalCommands
        );


        JenkinsSlave.RequestJenkinsSlave jenkinsSlave = new JenkinsSlave.SharedResourcesFirst(
                TEST_JENKINS_SLAVE_NAME,"label",1,
                "linkedItem", "dummy.host-na.me", 0L,
                0.2, TEST_JENKINS_SLAVE_MEM, Collections.emptySet(),"jenkins");


        SlaveRequest slaveReq = new SlaveRequest(jenkinsSlave, mesosSlaveInfo);
        SlaveResult slaveResult = Mockito.mock(SlaveResult.class);

        return new Request(slaveReq,slaveResult);
    }

    private Protos.Offer createOfferWithVariableRanges(long rangeBegin, long rangeEnd) {
        Protos.Value.Range range = Protos.Value.Range.newBuilder()
                .setBegin(rangeBegin)
                .setEnd(rangeEnd)
                .build();

        Protos.Value.Ranges ranges = Protos.Value.Ranges.newBuilder()
                .addRange(range)
                .build();

        Protos.Resource resource = Protos.Resource.newBuilder()
                .setName("ports")
                .setRanges(ranges)
                .setType(Protos.Value.Type.RANGES)
                .build();

        return Protos.Offer.newBuilder()
                .addResources(resource)
                .setId(Protos.OfferID.newBuilder().setValue("value").build())
                .setFrameworkId(Protos.FrameworkID.newBuilder().setValue("value").build())
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("value").build())
                .setHostname("hostname")
                .build();
    }
}
