package org.jenkinsci.plugins.mesos.scheduling;

import org.apache.mesos.Protos;
import org.jenkinsci.plugins.mesos.TestUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class)
public class LeaseTest {

    private Lease lease;
    private Date now;

    @Before
    public void setUp() {
        String role = "testRole";
        Protos.Offer offer = TestUtils.createOffer("O_1",
                TestUtils.createScalarResource("cpus", 2.0, TestUtils.SHARED_ROLE),
                TestUtils.createScalarResource("mem",  4.0, TestUtils.SHARED_ROLE),
                TestUtils.createScalarResource("cpus", 2.0, role),
                TestUtils.createScalarResource("mem",  4.0, role),
                TestUtils.createRangesResource("ports", 8000, 8010, role),
                TestUtils.createRangesResource("ports", 9000, 9010, TestUtils.SHARED_ROLE)
        );

        // let now be a tad in the past, to compensate for fast test execution
        now = new Date((new Date()).getTime() - TimeUnit.SECONDS.toMillis(10));

        long unavailabilityStartNanos = TimeUnit.MILLISECONDS.toNanos(now.getTime());
        long unavailabilityDurationNanos = TimeUnit.HOURS.toNanos(1);

        Protos.Unavailability unavailability = Protos.Unavailability.newBuilder()
                .setStart(Protos.TimeInfo.newBuilder().setNanoseconds(unavailabilityStartNanos).build())
                .setDuration(Protos.DurationInfo.newBuilder().setNanoseconds(unavailabilityDurationNanos).build())
                .build();

        offer = offer.toBuilder().setUnavailability(unavailability).build();

        lease = new Lease(offer);

    }

    @Test
    public void expectThatLeaseIsNotAvailableNow() {
        assertFalse(lease.isAvailableNow());
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartDateIsBetweenUnavailability() {
        Date startDate = new Date(now.getTime() + TimeUnit.MINUTES.toMillis(5));
        assertFalse(lease.isAvailable(startDate));
    }

    @Test
    public void expectThatLeaseIsAvailableWhenStartDateIsBeforeUnavailablityStarts() {
        Date startDate = new Date(now.getTime() - TimeUnit.MINUTES.toMillis(5));
        assertTrue(lease.isAvailable(startDate));
    }

    @Test
    public void expectThatLeaseIsAvailableWhenStartDateIsAfterUnavailabilityEnds() {
        Date startDate = new Date(now.getTime() + TimeUnit.HOURS.toMillis(2));
        assertTrue(lease.isAvailable(startDate));
    }


    @Test
    public void expectThatLeaseIsAvailableWhenStartAndEndDateAreBeforeUnavailabilityStarts() {
        Date startDate = new Date(now.getTime() - TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.MINUTES.toMillis(1);
        assertTrue(lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartDateIsBeforeAndEndDateIsBetweenUnavailability() {
        Date startDate = new Date(now.getTime() - TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.MINUTES.toMillis(6);
        assertFalse(lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartAndEndDateIsBetweenUnavailability() {
        Date startDate = new Date(now.getTime() + TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.MINUTES.toMillis(6);
        assertFalse(lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartDateIsBetweenAndEndDateAfterUnavailability() {
        Date startDate = new Date(now.getTime() + TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.HOURS.toMillis(1);
        assertFalse(lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsAvailableWhenStartAndEndDateAreAfterUnavailabilityEnds() {
        Date startDate = new Date(now.getTime() + TimeUnit.HOURS.toMillis(2));
        long duration = TimeUnit.MINUTES.toMillis(6);
        assertTrue(lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartIsBeforeAndEndAfterUnavailability() {
        Date startDate = new Date(now.getTime() - TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.HOURS.toMillis(2);
        assertFalse(lease.isAvailable(startDate, duration));
    }



    private Protos.Value.Range createRange(long begin, long end) {
        return Protos.Value.Range.newBuilder()
                .setBegin(begin)
                .setEnd(end)
                .build();
    }

    private Protos.Value.Ranges createRanges(Protos.Value.Range... ranges) {
        Protos.Value.Ranges.Builder rangesBuilder = Protos.Value.Ranges.newBuilder();

        for (Protos.Value.Range range : ranges) {
            rangesBuilder.addRange(range);
        }

        return rangesBuilder.build();
    }


    private Protos.TaskInfo createTaskInfo(String id, String slaveId, Protos.Resource... resources) {
        Protos.TaskInfo.Builder taskInfoBuilder = Protos.TaskInfo.newBuilder()
                .setTaskId(Protos.TaskID.newBuilder().setValue(id))
                .setName(id)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue(slaveId).build());

        for (Protos.Resource resource : resources) {
            taskInfoBuilder.addResources(resource);
        }

        return taskInfoBuilder.build();
    }

    //   req: 8000-8010; ava: 8000-8010; new: <empty>
    @Test
    public void expectThatARequestedPortRangeEqualToAnAvailableRangeIsAssigned() {
        boolean expectedAssigned = true;
        int expectedTotalSize = 1;
        List<Protos.Value.Range> expectedRoleRanges = Collections.emptyList();
        List<Protos.Value.Range> expectedSharedRanges = TestUtils.createRangesResource("ports", 9000, 9010, "*").getRanges().getRangeList();

        Protos.Resource resource = TestUtils.createRangesResource("ports", 8000, 8010, "testRole");
        Protos.TaskInfo taskInfo = createTaskInfo("task1", "slave1", resource);
        Request request = new Request(null ,null);

        boolean actualAssigned = lease.assign(request, taskInfo);
        int actualTotalSize = lease.getTotalAvailablePortResources().size();
        List<Protos.Value.Range> actualRoleRanges = lease.getAvailablePortResources("testRole");
        List<Protos.Value.Range> actualSharedRanges = lease.getAvailablePortResources("*");

        assertThat(actualAssigned, is(equalTo(expectedAssigned)));
        assertThat(actualTotalSize, is(equalTo(expectedTotalSize)));
        assertThat(actualRoleRanges, is(equalTo(expectedRoleRanges)));
        assertThat(actualSharedRanges, is(equalTo(expectedSharedRanges)));
    }

    //   req: 8080-8090; ava: 8000-8010; new: 8000-8010
    @Test
    public void expectThatARequestedPortRangeForSpecificRoleOutsideAvailableRoleRangesAreNotAssigned() {
        boolean expectedAssigned = false;
        int expectedTotalSize = 2;
        List<Protos.Value.Range> expectedRoleRanges = TestUtils.createRangesResource("ports", 8000, 8010, "testRole").getRanges().getRangeList();
        List<Protos.Value.Range> expectedSharedRanges = TestUtils.createRangesResource("ports", 9000, 9010, "*").getRanges().getRangeList();

        Protos.Resource resource = TestUtils.createRangesResource("ports", 9005, 9005, "testRole");
        Protos.TaskInfo taskInfo = createTaskInfo("task1", "slave1", resource);
        Request request = new Request(null ,null);

        boolean actualAssigned = lease.assign(request, taskInfo);
        int actualTotalSize = lease.getTotalAvailablePortResources().size();
        List<Protos.Value.Range> actualRoleRanges = lease.getAvailablePortResources("testRole");
        List<Protos.Value.Range> actualSharedRanges = lease.getAvailablePortResources("*");

        assertThat(actualAssigned, is(equalTo(expectedAssigned)));
        assertThat(actualTotalSize, is(equalTo(expectedTotalSize)));
        assertThat(actualRoleRanges, is(equalTo(expectedRoleRanges)));
        assertThat(actualSharedRanges, is(equalTo(expectedSharedRanges)));
    }

    //   req: 8000-8000; cur: 8000-8010; new: 8001-8010
    //   req: 8000-8009; cur: 8000-8010; new: 8010-8010
    @Test
    public void expectThatARequestedPortRangeBeginningWithAnAvailableRangeIsAssigned() {
        boolean expectedAssigned = true;
        int expectedTotalSize = 2;
        List<Protos.Value.Range> expectedRoleRanges = TestUtils.createRangesResource("ports", 8005, 8010, "testRole").getRanges().getRangeList();
        List<Protos.Value.Range> expectedSharedRanges = TestUtils.createRangesResource("ports", 9000, 9010, "*").getRanges().getRangeList();

        Protos.Resource resource = TestUtils.createRangesResource("ports", 8000, 8004, "testRole");
        Protos.TaskInfo taskInfo = createTaskInfo("task1", "slave1", resource);
        Request request = new Request(null ,null);

        boolean actualAssigned = lease.assign(request, taskInfo);
        int actualTotalSize = lease.getTotalAvailablePortResources().size();
        List<Protos.Value.Range> actualRoleRanges = lease.getAvailablePortResources("testRole");
        List<Protos.Value.Range> actualSharedRanges = lease.getAvailablePortResources("*");

        assertThat(actualAssigned, is(equalTo(expectedAssigned)));
        assertThat(actualTotalSize, is(equalTo(expectedTotalSize)));
        assertThat(actualRoleRanges, is(equalTo(expectedRoleRanges)));
        assertThat(actualSharedRanges, is(equalTo(expectedSharedRanges)));
    }

    //   req: 8008-8010; cur: 8000-8010; new: 8000-8007
    @Test
    public void expectThatARequestedPortRangeEndingWithAnAvailableRangeIsAssigned() {
        boolean expectedAssigned = true;
        int expectedTotalSize = 2;
        List<Protos.Value.Range> expectedRoleRanges = TestUtils.createRangesResource("ports", 8000, 8009, "testRole").getRanges().getRangeList();
        List<Protos.Value.Range> expectedSharedRanges = TestUtils.createRangesResource("ports", 9000, 9010, "*").getRanges().getRangeList();

        Protos.Resource resource = TestUtils.createRangesResource("ports", 8010, 8010, "testRole");
        Protos.TaskInfo taskInfo = createTaskInfo("task1", "slave1", resource);
        Request request = new Request(null ,null);

        boolean actualAssigned = lease.assign(request, taskInfo);
        int actualTotalSize = lease.getTotalAvailablePortResources().size();
        List<Protos.Value.Range> actualRoleRanges = lease.getAvailablePortResources("testRole");
        List<Protos.Value.Range> actualSharedRanges = lease.getAvailablePortResources("*");

        assertThat(actualAssigned, is(equalTo(expectedAssigned)));
        assertThat(actualTotalSize, is(equalTo(expectedTotalSize)));
        assertThat(actualRoleRanges, is(equalTo(expectedRoleRanges)));
        assertThat(actualSharedRanges, is(equalTo(expectedSharedRanges)));
    }

    //   req: 8008-8008; cur: 8000-8010; new: 8000-8007, 8009-8010
    //   req: 8008-8009; cur: 8000-8010; new: 8000-8007, 8010-8010
    @Test
    public void expectThatARequestedPortRangeBetweenAvailableRangesAreAssigned() {
        boolean expectedAssigned = true;
        int expectedTotalSize = 3;

        List<Protos.Value.Range> expectedRoleRanges = TestUtils.createRangesResource("ports", "testRole", createRanges(createRange(8003, 8007), createRange(8009, 8010))).getRanges().getRangeList();
        List<Protos.Value.Range> expectedSharedRanges = TestUtils.createRangesResource("ports", 9000, 9010, "*").getRanges().getRangeList();

        Protos.Resource resource = TestUtils.createRangesResource("ports", "testRole", createRanges(createRange(8000, 8002), createRange(8008, 8008)));
        Protos.TaskInfo taskInfo = createTaskInfo("task1", "slave1", resource);
        Request request = new Request(null ,null);

        boolean actualAssigned = lease.assign(request, taskInfo);
        int actualTotalSize = lease.getTotalAvailablePortResources().size();
        List<Protos.Value.Range> actualRoleRanges = lease.getAvailablePortResources("testRole");
        List<Protos.Value.Range> actualSharedRanges = lease.getAvailablePortResources("*");

        assertThat(actualAssigned, is(equalTo(expectedAssigned)));
        assertThat(actualTotalSize, is(equalTo(expectedTotalSize)));
        assertThat(actualRoleRanges, is(equalTo(expectedRoleRanges)));
        assertThat(actualSharedRanges, is(equalTo(expectedSharedRanges)));
    }

    @Test
    public void expectThatOneOfMultipleRequestedPortRangesOutsideAvaiableRangesNothingIsAssigned() {
        boolean expectedAssigned = false;
        int expectedTotalSize = 2;

        double expectedMem = lease.getAvailableMem("testRole");
        double expectedTotalMem = lease.getTotalAvailableMem();

        List<Protos.Value.Range> expectedRoleRanges = TestUtils.createRangesResource("ports", 8000, 8010, "testRole").getRanges().getRangeList();
        List<Protos.Value.Range> expectedSharedRanges = TestUtils.createRangesResource("ports", 9000, 9010, "*").getRanges().getRangeList();

        Protos.Resource scalarResource = TestUtils.createScalarResource("mem", 2, "testRole");
        Protos.Resource resource = TestUtils.createRangesResource("ports", "testRole", createRanges(createRange(8001, 8001), createRange(9005, 9008)));
        Protos.TaskInfo taskInfo = createTaskInfo("task1", "slave1", resource, scalarResource);
        Request request = new Request(null ,null);

        boolean actualAssigned = lease.assign(request, taskInfo);
        int actualTotalSize = lease.getTotalAvailablePortResources().size();
        List<Protos.Value.Range> actualRoleRanges = lease.getAvailablePortResources("testRole");
        List<Protos.Value.Range> actualSharedRanges = lease.getAvailablePortResources("*");

        double actualMem = lease.getAvailableMem("testRole");
        double actualTotalMem = lease.getTotalAvailableMem();

        assertThat(actualAssigned, is(equalTo(expectedAssigned)));
        assertThat(actualTotalSize, is(equalTo(expectedTotalSize)));
        assertThat(actualRoleRanges, is(equalTo(expectedRoleRanges)));
        assertThat(actualSharedRanges, is(equalTo(expectedSharedRanges)));

        assertThat(actualMem, is(equalTo(expectedMem)));
        assertThat(actualTotalMem, is(equalTo(expectedTotalMem)));
    }

    @Test
    public void expectThatMultipleRequestedPortRangesBetweenAvaiableRangesAreAssigned() {
        boolean expectedAssigned = true;
        int expectedTotalSize = 4;

        List<Protos.Value.Range> expectedRoleRanges = TestUtils.createRangesResource("ports", "testRole", createRanges(createRange(8000, 8000), createRange(8002, 8004), createRange(8009, 8010))).getRanges().getRangeList();
        List<Protos.Value.Range> expectedSharedRanges = TestUtils.createRangesResource("ports", 9000, 9010, "*").getRanges().getRangeList();

        Protos.Resource resource = TestUtils.createRangesResource("ports", "testRole", createRanges(createRange(8001, 8001), createRange(8005, 8008)));
        Protos.TaskInfo taskInfo = createTaskInfo("task1", "slave1", resource);
        Request request = new Request(null ,null);

        boolean actualAssigned = lease.assign(request, taskInfo);
        int actualTotalSize = lease.getTotalAvailablePortResources().size();
        List<Protos.Value.Range> actualRoleRanges = lease.getAvailablePortResources("testRole");
        List<Protos.Value.Range> actualSharedRanges = lease.getAvailablePortResources("*");

        assertThat(actualAssigned, is(equalTo(expectedAssigned)));
        assertThat(actualTotalSize, is(equalTo(expectedTotalSize)));
        assertThat(actualRoleRanges, is(equalTo(expectedRoleRanges)));
        assertThat(actualSharedRanges, is(equalTo(expectedSharedRanges)));
    }


    @Test
    public void expectThatRequestetPortsAreNotAssignableWhenNoAvailablePortsForRole() {
        String role = "testRole";
        Protos.Offer offer = TestUtils.createOffer("O_1",
                TestUtils.createScalarResource("cpus", 2.0, TestUtils.SHARED_ROLE),
                TestUtils.createScalarResource("mem",  4.0, TestUtils.SHARED_ROLE),
                TestUtils.createScalarResource("cpus", 2.0, role),
                TestUtils.createScalarResource("mem",  4.0, role)
                //TestUtils.createRangesResource("ports", 9000, 9010, TestUtils.SHARED_ROLE)
        );
        lease = new Lease(offer);

        boolean expectedAssigned = false;
        int expectedTotalSize = 0;

        List<Protos.Value.Range> expectedRoleRanges = Collections.emptyList();
        List<Protos.Value.Range> expectedSharedRanges = Collections.emptyList();
        //List<Protos.Value.Range> expectedSharedRanges = TestUtils.createRangesResource("ports", 9000, 9010, "*").getRanges().getRangeList();

        Protos.Resource resource = TestUtils.createRangesResource("ports", "testRole", createRanges(createRange(8001, 8001), createRange(8005, 8008)));
        Protos.TaskInfo taskInfo = createTaskInfo("task1", "slave1", resource);
        Request request = new Request(null ,null);

        boolean actualAssigned = lease.assign(request, taskInfo);
        int actualTotalSize = lease.getTotalAvailablePortResources().size();
        List<Protos.Value.Range> actualRoleRanges = lease.getAvailablePortResources("testRole");
        List<Protos.Value.Range> actualSharedRanges = lease.getAvailablePortResources("*");

        assertThat(actualAssigned, is(equalTo(expectedAssigned)));
        assertThat(actualTotalSize, is(equalTo(expectedTotalSize)));
        assertThat(actualRoleRanges, is(equalTo(expectedRoleRanges)));
        assertThat(actualSharedRanges, is(equalTo(expectedSharedRanges)));
    }
}
