package org.jenkinsci.plugins.mesos;

import hudson.model.Node;
import org.apache.mesos.Protos;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.scheduling.*;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Set;

public class TestUtils {

    public static final String SHARED_ROLE = "*";

    public static Request createRequest(JenkinsSlave.RequestJenkinsSlave jenkinsAgent) {
        MesosCloud mockMesosCloud = Mockito.mock(MesosCloud.class);

        MesosSlaveInfo mesosAgentInfo = new MesosSlaveInfo(
                "mockLabel",
                Node.Mode.EXCLUSIVE,
                "0",
                "0",
                "1",
                Double.toString(jenkinsAgent.getCpus()),
                Integer.toString((int) jenkinsAgent.getMem()),
                "/mockFSROOT",
                "10",
                "10",
                true,
                "{'mock' : 'attribute'}",
                "-Xmx1G",
                "-noReconnect",
                null,
                Collections.<MesosSlaveInfo.URI>emptyList(),
                null,
                Collections.<MesosSlaveInfo.Command>emptyList()
        );

        SlaveRequest agentRequest = new SlaveRequest(jenkinsAgent, mesosAgentInfo);
        SlaveResult agentResult = new SlaveResult(mockMesosCloud);

        return new Request(agentRequest, agentResult);
    }

    public static Request createSharedResourcesFirstRequest(double cpus, double mem, String role) {
        return createSharedResourcesFirstRequest(cpus, mem, Collections.<MesosSlaveInfo.PortMapping>emptySet(), role);
    }

    public static Request createSharedResourcesFirstRequest(double cpus, double mem, Set<MesosSlaveInfo.PortMapping> portMappings, String role) {
        JenkinsSlave.RequestJenkinsSlave jenkinsAgent = new JenkinsSlave.SharedResourcesFirst(
                "MockSlave",
                "mockLabel",
                1, // numExecutors
                "mockLinkedItem", // linkedItem
                "dummy.host-na.me",
                0L,
                cpus, // cpus
                mem, // mem
                portMappings,
                role // role
        );

        return createRequest(jenkinsAgent);
    }

    public static Request createRoleResourcesFirstRequest(double cpus, double mem, String role) {
        return createRoleResourcesFirstRequest(cpus, mem, Collections.<MesosSlaveInfo.PortMapping>emptySet(), role);
    }

    public static Request createRoleResourcesFirstRequest(double cpus, double mem, Set<MesosSlaveInfo.PortMapping> portMappings, String role) {
        JenkinsSlave.RequestJenkinsSlave jenkinsAgent = new JenkinsSlave.RoleResourcesFirst(
                "MockSlave",
                "mockLabel",
                1, // numExecutors
                "mockLinkedItem", // linkedItem
                "dummy.host-na.me",
                0L,
                cpus, // cpus
                mem, // mem
                portMappings,
                role // role
        );

        return createRequest(jenkinsAgent);
    }

    public static Request createRoleCappedRequest(double cpus, double mem, String role) {
        return createRoleCappedRequest(cpus, mem, Collections.<MesosSlaveInfo.PortMapping>emptySet(), role);
    }

    public static Request createRoleCappedRequest(double cpus, double mem, Set<MesosSlaveInfo.PortMapping> portMappings, String role) {
        JenkinsSlave.RequestJenkinsSlave jenkinsAgent = new JenkinsSlave.RoleCapped(
                "MockSlave",
                "mockLabel",
                1, // numExecutors
                "mockLinkedItem", // linkedItem
                "dummy.host-na.me",
                0L,
                cpus, // cpus
                mem, // mem
                portMappings,
                role // role
        );

        return createRequest(jenkinsAgent);
    }


    public static Protos.Resource createRangesResource(String name, String role, Protos.Value.Ranges ranges) {
        return Protos.Resource.newBuilder()
                .setName(name)
                .setRanges(ranges)
                .setType(Protos.Value.Type.RANGES)
                .setRole(role)
                .build();
    }

    @SuppressWarnings("deprecation")
    public static Protos.Resource createRangesResource(String name, long rangeBegin, long rangeEnd, String role) {
        Protos.Value.Range range = Protos.Value.Range.newBuilder()
                .setBegin(rangeBegin)
                .setEnd(rangeEnd)
                .build();

        Protos.Value.Ranges ranges = Protos.Value.Ranges.newBuilder()
                .addRange(range)
                .build();

        return createRangesResource(name, role, ranges);
    }

    @SuppressWarnings("deprecation")
    public static Protos.Resource createScalarResource(String name, double value, String role) {
        Protos.Value.Scalar scalar = Protos.Value.Scalar.newBuilder()
                .setValue(value)
                .build();

        return Protos.Resource.newBuilder()
                .setName(name)
                .setScalar(scalar)
                .setType(Protos.Value.Type.SCALAR)
                .setRole(role)
                .build();

    }

    public static Protos.Offer createOffer(String offerId, Protos.Resource... resources) {
        String frameworkId = "FW_001";
        String agentId = "A_" + offerId;
        String hostname = offerId + ".test.net";

        // TODO: make parmeterizable
        Protos.Attribute attribute = Protos.Attribute.newBuilder()
                .setName("mock")
                .setText(Protos.Value.Text.newBuilder().setValue("attribute").build())
                .setType(Protos.Value.Type.TEXT)
                .build();


        Protos.Offer.Builder offerBuilder = Protos.Offer.newBuilder()
                .setId(Protos.OfferID.newBuilder().setValue(offerId).build())
                .setFrameworkId(Protos.FrameworkID.newBuilder().setValue(frameworkId).build())
                .setSlaveId(Protos.SlaveID.newBuilder().setValue(agentId).build())
                .setHostname(hostname)
                .addAttributes(attribute);

        for (Protos.Resource resource: resources) {
            offerBuilder.addResources(resource);
        }


        return offerBuilder.build();
    }

    public static Lease createLease(String offerId, Protos.Resource... resources) {
        return createLease(createOffer(offerId, resources));
    }

    public static Lease createLease(Protos.Offer offer) {
        return new Lease(offer);
    }

}
