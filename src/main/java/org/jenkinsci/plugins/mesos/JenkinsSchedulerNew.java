package org.jenkinsci.plugins.mesos;

import org.apache.commons.lang.StringUtils;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.jenkinsci.plugins.mesos.scheduling.Lease;
import org.jenkinsci.plugins.mesos.scheduling.Request;
import org.jenkinsci.plugins.mesos.scheduling.creator.TaskCreator;
import org.jenkinsci.plugins.mesos.scheduling.fitness.FitnessRater;
import org.jenkinsci.plugins.mesos.scheduling.fitness.NodeAffineRaters;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class JenkinsSchedulerNew extends JenkinsScheduler {

    private final FitnessRater fitnessRater;


    public JenkinsSchedulerNew(String jenkinsMaster, MesosCloud mesosCloud) {
        this(jenkinsMaster, mesosCloud, NodeAffineRaters.NODE_AFFINE_CPU_MEM_SPREAD);
    }


    public JenkinsSchedulerNew(String jenkinsMaster, MesosCloud mesosCloud, FitnessRater fitnessRater) {
        super(jenkinsMaster, mesosCloud, "JenkinsScheduler (new)");

        // TODO: configurable fitness rater (on request with mapping like "FrameworkToItem"?)
        this.fitnessRater = fitnessRater;
    }

    @Override
    protected void resourceOffersImpl(SchedulerDriver driver, List<Protos.Offer> offers) {
        List<Protos.Offer> offersToDecline;
        double declineOfferDuration = 1;

        // drain/move requests to a separate list, so that we try not to be greedy
        List<Request> currentRequests = drainRequests();

        if (!currentRequests.isEmpty()) {
            // create leases list from offers
            List<Lease> leases = createLeases(offers);

            // try to assign requests
            List<Request> unassignedRequests = assignRequests(currentRequests, leases);

            // add still unassigned requests back to requests (finally block?)
            addRequests(unassignedRequests);

            offersToDecline = launchAssignments(driver, leases);
        } else {
            // Decline offer for a longer period if no slave is waiting to get spawned.
            // This prevents unnecessarily getting offers every few seconds and causing
            // starvation when running a lot of frameworks.
            declineOfferDuration = getNoRequestsDeclineOfferDuration();
            LOGGER.info("No requests in queue, framework '" + getMesosCloud().getFrameworkName() + "' rejects offers for " + declineOfferDuration + "s");
            offersToDecline = offers;
        }

        declineOffers(driver, offersToDecline, Protos.Filters.newBuilder().setRefuseSeconds(declineOfferDuration).build());
    }

    private List<Protos.Offer> launchAssignments(SchedulerDriver driver, List<Lease> leases) {
        // launch tasks / decline other offers/leases
        // TODO: what if launchMesosTask/declineOffer goes awry? -> add requests of unhandled leases back to requests as well
        List<Protos.Offer> offersToDecline = new ArrayList<Protos.Offer>();

        for (Lease lease : leases) {
            try {
                if (lease.hasAssignments()) {
                    // launch tasks
                    launchMesosTasks(driver, lease);
                } else {
                    // decline leases with no assignments
                    offersToDecline.addAll(lease.getOffers());
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unable to launch tasks for lease '" + lease + "':", e);
                offersToDecline.addAll(lease.getOffers());
            }
        }

        return  offersToDecline;
    }

    private boolean assignToFittestLease(Request request, List<Lease> leases) {
        // find fittest lease/offer
        // (this part could be multi-threaded)
        Lease fittestLease = findFittestLease(request, leases);

        // assign request to fittest lease/offer ("create task")
        return fittestLease != null && fittestLease.assign(request, new TaskCreator(request, fittestLease, this).createTask());
    }

    private List<Request> assignRequests(@Nonnull List<Request> currentRequests, @Nonnull List<Lease> leases) {
        List<Request> unassignedRequests = new ArrayList<Request>();

        // try to assign requests to a lease
        for (Request request : currentRequests) {
            try {
                if (!(isExistingRequest(request, leases) || assignToFittestLease(request, leases))) {
                    unassignedRequests.add(request);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unable to assign request '" + request + "' to a lease:", e);
                unassignedRequests.add(request);
            }
        }

        return unassignedRequests;
    }

    private boolean isRequestAlreadyAssigned(String agentName, List<Lease> leases) {
        for (Lease lease : leases) {
            for (Request existingRequest : lease.getAssignedRequests()) {
                String existingAgentName = existingRequest.getRequest().getSlave().getName();
                if (StringUtils.equals(agentName, existingAgentName)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isExistingRequest(Request request, List<Lease> leases) {
        // dont test fitness if first part of createMesos applies:
        // * is existing task
        // * is existing jenkins agent
        // (task for request already exists, or jenkins agent already created for it)
        // in this case simply remove request from currentRequests and ignore (log it though)
        String agentName = request.getRequest().getSlave().getName();
        return isRequestAlreadyAssigned(agentName, leases) || isExistingTaskOrAgent(agentName);
    }

    private void declineOffers(SchedulerDriver driver, List<Protos.Offer> offers, Protos.Filters filters) {
        // lock object to not allow a driver.revive() (revive should wait until all offers declined)
        for (Protos.Offer offer : offers) {
            try {
                declineOffer(driver, offer, filters);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unable to decline offer '" + offer + "':", e);
            }
        }
        // unlock object
    }

    private List<Lease> createLeases(List<Protos.Offer> offers) {
        List<Lease> leases = new ArrayList<Lease>(offers.size());

        for (Protos.Offer offer : offers) {
            leases.add(new Lease(offer));
        }

        return leases;
    }


    private Lease findFittestLease(Request request, List<Lease> leases) {
        Lease fittestLease = null;
        double fittestRating = FitnessRater.NOT_FIT;

        for (Lease lease : leases) {
            double currentRating = fitnessRater.rateFitness(request, lease);

            if (currentRating > fittestRating) {
                fittestLease = lease;
                fittestRating = currentRating;
            }
        }

        return fittestLease;
    }


    private void launchMesosTasks(SchedulerDriver driver, Lease lease) {
        launchMesosTasks(driver, lease.getOfferIds(), lease.getAssignments(), lease.getHostname());
    }

}
