package org.jenkinsci.plugins.mesos.scheduling;

import org.apache.mesos.Protos;
import org.jenkinsci.plugins.mesos.TestUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

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
                TestUtils.createRangesResource("ports", 8080, 8080, role),
                TestUtils.createRangesResource("ports", 8443, 8443, TestUtils.SHARED_ROLE)
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
        assertEquals(false, lease.isAvailableNow());
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartDateIsBetweenUnavailability() {
        Date startDate = new Date(now.getTime() + TimeUnit.MINUTES.toMillis(5));
        assertEquals(false, lease.isAvailable(startDate));
    }

    @Test
    public void expectThatLeaseIsAvailableWhenStartDateIsBeforeUnavailablityStarts() {
        Date startDate = new Date(now.getTime() - TimeUnit.MINUTES.toMillis(5));
        assertEquals(true, lease.isAvailable(startDate));
    }

    @Test
    public void expectThatLeaseIsAvailableWhenStartDateIsAfterUnavailabilityEnds() {
        Date startDate = new Date(now.getTime() + TimeUnit.HOURS.toMillis(2));
        assertEquals(true, lease.isAvailable(startDate));
    }


    @Test
    public void expectThatLeaseIsAvailableWhenStartAndEndDateAreBeforeUnavailabilityStarts() {
        Date startDate = new Date(now.getTime() - TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.MINUTES.toMillis(1);
        assertEquals(true, lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartDateIsBeforeAndEndDateIsBetweenUnavailability() {
        Date startDate = new Date(now.getTime() - TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.MINUTES.toMillis(6);
        assertEquals(false, lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartAndEndDateIsBetweenUnavailability() {
        Date startDate = new Date(now.getTime() + TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.MINUTES.toMillis(6);
        assertEquals(false, lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartDateIsBetweenAndEndDateAfterUnavailability() {
        Date startDate = new Date(now.getTime() + TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.HOURS.toMillis(1);
        assertEquals(false, lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsAvailableWhenStartAndEndDateAreAfterUnavailabilityEnds() {
        Date startDate = new Date(now.getTime() + TimeUnit.HOURS.toMillis(2));
        long duration = TimeUnit.MINUTES.toMillis(6);
        assertEquals(true, lease.isAvailable(startDate, duration));
    }

    @Test
    public void expectThatLeaseIsNotAvailableWhenStartIsBeforeAndEndAfterUnavailability() {
        Date startDate = new Date(now.getTime() - TimeUnit.MINUTES.toMillis(5));
        long duration = TimeUnit.HOURS.toMillis(2);
        assertEquals(false, lease.isAvailable(startDate, duration));
    }

}
