package org.jenkinsci.plugins.mesos;

import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.jenkinsci.plugins.mesos.scheduling.Lease;
import org.jenkinsci.plugins.mesos.scheduling.Request;
import org.jenkinsci.plugins.mesos.scheduling.fitness.NodeAffineRaters;

import java.util.ArrayList;
import java.util.List;

public class JenkinsSchedulerNew extends JenkinsScheduler {


    public JenkinsSchedulerNew(String jenkinsMaster, MesosCloud mesosCloud) {
        super(jenkinsMaster, mesosCloud, "JenkinsScheduler (new)");
    }


    @Override
    protected void resourceOffersImpl(SchedulerDriver driver, List<Protos.Offer> offers) {
        List<Protos.Offer> offersToDecline;
        double declineOfferDuration = 0;

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
            LOGGER.info("No requests in queue, framework '" + getMesosCloud().getFrameworkName() + "' rejects offers for " + getDeclineOfferDuration() + "s");
            offersToDecline = offers;
            declineOfferDuration = getDeclineOfferDuration();
        }

        declineOffers(driver, offersToDecline, Protos.Filters.newBuilder().setRefuseSeconds(declineOfferDuration).build());
    }

    private List<Protos.Offer> launchAssignments(SchedulerDriver driver, List<Lease> leases) {
        // launch tasks / decline other offers/leases
        // TODO: what if launchMesosTask/declineOffer goes awry? -> add requests of unhandled leases back to requests as well
        List<Protos.Offer> offersToDecline = new ArrayList<Protos.Offer>();

        for (Lease lease : leases) {
            // try
            if (lease.hasAssignments()) {
                // launch tasks
                launchMesosTasks(driver, lease);
            } else {
                // decline leases with no assignments
                offersToDecline.addAll(lease.getOffers());
            }
            // catch
            //  requests.addAll(lease.getAssignments().values())
        }

        return  offersToDecline;
    }

    private boolean assignToFittestLease(Request request, List<Lease> leases) {
        // find fittest lease/offer
        // in theory this part could be multi-threaded
        Lease fittestLease = findFittestLease(request, leases);

        // assign request to fittest lease/offer ("create task")
        //return fittestLease != null && fittestLease.assign(request);
        return true;
    }

    private List<Request> assignRequests(List<Request> currentRequests, List<Lease> leases) {
        List<Request> unassignedRequests = new ArrayList<Request>();

        // try to assign requests to a lease
        for (Request request : currentRequests) {
            if (!(isExistingRequest(request) || assignToFittestLease(request, leases))) {
                unassignedRequests.add(request);
            }
        }

        return unassignedRequests;
    }

    private boolean isExistingRequest(Request request) {
        // dont test fitness if first part of createMesos applies:
        // * is existing task
        // * is existing jenkins agent
        // (task for request already exists, or jenkins agent already created for it)
        // in this case simply remove request from currentRequests and ignore (log it though)

        // TODO: check assigments of leases for existing request as well

        String agentName = request.getRequest().getSlave().getName();
        return isExistingTaskOrAgent(agentName);
    }

    private void declineOffers(SchedulerDriver driver, List<Protos.Offer> offers, Protos.Filters filters) {
        // lock object to not allow a driver.revive() (revive should wait until all offers declined)
        for (Protos.Offer offer : offers) {
            declineOffer(driver, offer, filters);
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

        double bestFitness = 0;
        for (Lease lease : leases) {
            // TODO: configurable fitness rater
            double currentFitness = NodeAffineRaters.NODE_AFFINE_CPU_MEM_SPREAD.rateFitness(request, lease);
            if (currentFitness > bestFitness) {
                fittestLease = lease;
            }
        }

        return fittestLease;
    }


    private void launchMesosTasks(SchedulerDriver driver, Lease lease) {
        launchMesosTasks(driver, lease.getOfferIds(), lease.getAssignments(), lease.getHostname());
    }

}
