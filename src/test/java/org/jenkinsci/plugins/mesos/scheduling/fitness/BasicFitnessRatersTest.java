package org.jenkinsci.plugins.mesos.scheduling.fitness;

import org.jenkinsci.plugins.mesos.TestUtils;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.scheduling.Lease;
import org.jenkinsci.plugins.mesos.scheduling.Request;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.*;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

@RunWith(Parameterized.class)
public class BasicFitnessRatersTest {

    @Parameterized.Parameters(name = "expect {0} for {1} rater and {2} request")
    public static List<Object[]> getFitnessRaters() {

        String role = "testRole";
        Lease lease = TestUtils.createLease("O_1",
                TestUtils.createScalarResource("cpus", 2.0, TestUtils.SHARED_ROLE),
                TestUtils.createScalarResource("mem",  4.0, TestUtils.SHARED_ROLE),
                TestUtils.createScalarResource("cpus", 2.0, role),
                TestUtils.createScalarResource("mem",  4.0, role),
                TestUtils.createRangesResource("ports", 8080, 8080, role),
                TestUtils.createRangesResource("ports", 8443, 8443, TestUtils.SHARED_ROLE)
        );


        Set<MesosSlaveInfo.PortMapping> noPortMappings = Collections.emptySet();

        Set<MesosSlaveInfo.PortMapping> assignableRolePortMappings = new LinkedHashSet<>();
        assignableRolePortMappings.add(new MesosSlaveInfo.PortMapping(
                443,
                null,
                "tcp",
                "HTTPS port",
                null));

        Set<MesosSlaveInfo.PortMapping> assignablePortMappings = new LinkedHashSet<>(assignableRolePortMappings);
        assignablePortMappings.add(new MesosSlaveInfo.PortMapping(
                80,
                8080,
                "tcp",
                "HTTP port",
                null));

        Set<MesosSlaveInfo.PortMapping> unassignablePortMappings = new LinkedHashSet<>(assignableRolePortMappings);
        unassignablePortMappings.add(new MesosSlaveInfo.PortMapping(
                80,
                8081,
                "tcp",
                "HTTP port",
                null));

        Set<MesosSlaveInfo.PortMapping> tooManyPortMappings = new LinkedHashSet<>(assignablePortMappings);
        tooManyPortMappings.add(new MesosSlaveInfo.PortMapping(
                666,
                null,
                "tcp",
                "Devlish port",
                null));


        return Arrays.asList(
                new Object[][] {
                  {"FITTEST rating with enough of all resources",     BasicFitnessRaters.ASSIGNABLE, TestUtils.createSharedResourcesFirstRequest(3.5, 8.0,  assignablePortMappings,   role), lease, FitnessRater.FITTEST},
                  {"FITTEST rating with enough resources (no ports)", BasicFitnessRaters.ASSIGNABLE, TestUtils.createSharedResourcesFirstRequest(3.5, 8.0,  noPortMappings,           role), lease, FitnessRater.FITTEST},
                  {"NOT_FIT rating with not enough cpu resources",    BasicFitnessRaters.ASSIGNABLE, TestUtils.createSharedResourcesFirstRequest(5.0, 8.0,  assignablePortMappings,   role), lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with not enough mem resources",    BasicFitnessRaters.ASSIGNABLE, TestUtils.createSharedResourcesFirstRequest(4.0, 16.0, assignablePortMappings,   role), lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with not enough port resources",   BasicFitnessRaters.ASSIGNABLE, TestUtils.createSharedResourcesFirstRequest(3.5, 8.0,  tooManyPortMappings,      role), lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with unassignable host ports",     BasicFitnessRaters.ASSIGNABLE, TestUtils.createSharedResourcesFirstRequest(3.5, 16.0, unassignablePortMappings, role), lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with not enough resources",        BasicFitnessRaters.ASSIGNABLE, TestUtils.createSharedResourcesFirstRequest(8.0, 16.0, tooManyPortMappings,      role), lease, FitnessRater.NOT_FIT},

                  {"FITTEST rating with enough of all resources",     BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleResourcesFirstRequest(3.5, 8.0,  assignablePortMappings,   role),   lease, FitnessRater.FITTEST},
                  {"FITTEST rating with enough resources (no ports)", BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleResourcesFirstRequest(3.5, 8.0,  noPortMappings,           role),   lease, FitnessRater.FITTEST},
                  {"NOT_FIT rating with not enough cpu resources",    BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleResourcesFirstRequest(5.0, 8.0,  assignablePortMappings,   role),   lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with not enough mem resources",    BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleResourcesFirstRequest(4.0, 16.0, assignablePortMappings,   role),   lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with not enough port resources",   BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleResourcesFirstRequest(3.5, 8.0,  tooManyPortMappings,      role),   lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with unassignable host ports",     BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleResourcesFirstRequest(3.5, 16.0, unassignablePortMappings, role),   lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with not enough resources",        BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleResourcesFirstRequest(8.0, 16.0, tooManyPortMappings,      role),   lease, FitnessRater.NOT_FIT},

                  {"FITTEST rating with enough of all resources",     BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleCappedRequest(1.5, 4.0,  assignableRolePortMappings, role),         lease, FitnessRater.FITTEST},
                  {"FITTEST rating with enough resources (no ports)", BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleCappedRequest(1.5, 4.0,  noPortMappings,             role),         lease, FitnessRater.FITTEST},
                  {"NOT_FIT rating with not enough cpu resources",    BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleCappedRequest(5.0, 8.0,  assignablePortMappings,     role),         lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with not enough mem resources",    BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleCappedRequest(4.0, 16.0, assignablePortMappings,     role),         lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with not enough port resources",   BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleCappedRequest(3.5, 8.0,  tooManyPortMappings,        role),         lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with unassignable host ports",     BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleCappedRequest(3.5, 16.0, unassignablePortMappings,   role),         lease, FitnessRater.NOT_FIT},
                  {"NOT_FIT rating with not enough resources",        BasicFitnessRaters.ASSIGNABLE, TestUtils.createRoleCappedRequest(8.0, 16.0, tooManyPortMappings,        role),         lease, FitnessRater.NOT_FIT}
                }
        );
    }

    private final String name;
    private final FitnessRater fitnessRater;
    private final Request request;
    private final Lease lease;
    private final double expectedFitness;

    public BasicFitnessRatersTest(String name, FitnessRater fitnessRater, Request request, Lease lease, double expectedFitness) {
        this.name = name;
        this.fitnessRater = fitnessRater;
        this.request = request;
        this.lease = lease;
        this.expectedFitness = expectedFitness;
    }


    @Test
    public void testFitnessRating() {
        double actualFitness = fitnessRater.rateFitness(request, lease);
        assertThat(actualFitness, is(equalTo(expectedFitness)));
    }

}
