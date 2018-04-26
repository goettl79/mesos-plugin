package org.jenkinsci.plugins.mesos;

import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.scheduling.Request;
import org.jenkinsci.plugins.mesos.scheduling.SlaveRequest;
import org.jenkinsci.plugins.mesos.scheduling.creator.TaskCreator;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class JenkinsSchedulerOld extends JenkinsScheduler {

    public JenkinsSchedulerOld(String jenkinsMaster, MesosCloud mesosCloud) {
        super(jenkinsMaster, mesosCloud, "JenkinsScheduler (old)");
    }

    @Override
    protected void resourceOffersImpl(SchedulerDriver driver, List<Protos.Offer> offers) {
        List<Request> requests = drainRequests();

        try {
            for (Protos.Offer offer : offers) {
                /*
                 * TODO: IMHO this is "dangerous" because it could interfere with reviveOffers() and possible lead
                 *       to unintended waiting times if a viable offer for the newly added request got revived got declined before
                 *       in this loop. Introduce a kind of locking mechanism somehow....
                 */
                if (requests.isEmpty()) {
                    // Decline offer for a longer period if no slave is waiting to get spawned.
                    // This prevents unnecessarily getting offers every few seconds and causing
                    // starvation when running a lot of frameworks.
                    double declineOfferDuration = getNoRequestsDeclineOfferDuration();
                    LOGGER.info("No requests in queue, framework '" + getMesosCloud().getFrameworkName() + "' rejects offers for " + declineOfferDuration + " s");
                    Protos.Filters filters = Protos.Filters.newBuilder().setRefuseSeconds(declineOfferDuration).build();
                    declineOffer(driver, offer, filters);
                    continue;
                }

                // get matching offer for request, and create task
                Protos.TaskInfo task = null;
                if (isOfferAvailable(offer)) {
                    for (Request request : requests) {
                        if (matches(offer, request)) {
                            LOGGER.fine("Offer matched! Creating mesos task");

                            try {
                                //task = createMesosTask(offer, request);
                                if (!isExistingTaskOrAgent(request.getRequest().getSlave().getName())) {
                                    task = new TaskCreator(request, offer, this).createTask();
                                } else {
                                    LOGGER.warning("Task '" + request.getRequest().getSlave().getName() + "' already exists, don't create anything");
                                }

                                // launch task for request
                                if (task != null) {
                                    launchMesosTask(driver, offer, task, request);
                                }
                            } catch (Exception e) {
                                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                            }
                            requests.remove(request);
                            break;
                        }
                    }
                }

                // refuse/decline offer if no task was created
                if (task == null) {
                    declineOffer(driver, offer);
                }
            }
        } finally {
            // re-add unprocessed requests
            addRequests(requests);
        }
    }


    private boolean isOfferAvailable(Protos.Offer offer) {
        if(offer.hasUnavailability()) {
            Protos.Unavailability unavailability = offer.getUnavailability();

            Date startTime = new Date(TimeUnit.NANOSECONDS.toMillis(unavailability.getStart().getNanoseconds()));
            long duration = unavailability.getDuration().getNanoseconds();
            Date endTime = new Date(startTime.getTime() + TimeUnit.NANOSECONDS.toMillis(duration));
            Date currentTime = new Date();

            return !(startTime.before(currentTime) && endTime.after(currentTime));
        }

        return true;
    }

    private boolean matches(Protos.Offer offer, Request request) {
        double cpus = 0;
        double mem = 0;
        List<Protos.Value.Range> ports = new ArrayList<>();

        for (Protos.Resource resource : offer.getResourcesList()) {
            String resourceRole = resource.getRole();

            // Start: Not needed, b/c FW only gets resources of * and framework.setRole()
            String expectedRole = getMesosCloud().getRole();
            if (! (resourceRole.equals(expectedRole) || resourceRole.equals("*"))) {
                LOGGER.warning("Resource role " + resourceRole +
                        " doesn't match expected role " + expectedRole);
                continue;
            }
            // End: Not needed, b/c FW only gets resources of * and framework.setRole()

            // Add resources of * and role
            switch (resource.getName()) {
                case "cpus":
                    if (resource.getType().equals(Protos.Value.Type.SCALAR)) {
                        cpus += resource.getScalar().getValue();
                    } else {
                        LOGGER.severe("Cpus resource was not a scalar: " + resource.getType().toString());
                    }
                    break;
                case "mem":
                    if (resource.getType().equals(Protos.Value.Type.SCALAR)) {
                        mem += resource.getScalar().getValue();
                    } else {
                        LOGGER.severe("Mem resource was not a scalar: " + resource.getType().toString());
                    }
                    break;
                case "disk":
                    LOGGER.fine("Ignoring disk resources from offer");
                    break;
                case "ports":
                    if (resource.getType().equals(Protos.Value.Type.RANGES)) {
                        ports.addAll(resource.getRanges().getRangeList());
                    } else {
                        LOGGER.severe("Ports resource was not a range: " + resource.getType().toString());
                    }
                    break;
                default:
                    LOGGER.warning("Ignoring unknown resource type: " + resource.getName());
                    break;
            }
        }

        SlaveRequest slaveRequest = request.getRequest();
        MesosSlaveInfo slaveInfo = slaveRequest.getSlaveInfo();

        MesosSlaveInfo.ContainerInfo containerInfo = slaveInfo.getContainerInfo();

        boolean hasPortMappings = containerInfo != null && containerInfo.hasPortMappings();

        boolean hasPortResources = !ports.isEmpty();

        if (hasPortMappings && !hasPortResources) {
            LOGGER.severe("No ports resource present");
        }

        // Check for sufficient cpu and memory resources in the offer.
        double requestedCpus = slaveRequest.getSlave().getCpus();
        double requestedMem = slaveRequest.getSlave().getMem();
        // Get matching slave attribute for this label.
        JSONObject slaveAttributes = getMesosCloud().getSlaveAttributeForLabel(slaveInfo.getLabelString());

        if (requestedCpus <= cpus
                && requestedMem <= mem
                && !(hasPortMappings && !hasPortResources)
                && slaveAttributesMatch(offer, slaveAttributes)) {
            return true;
        } else {
            String requestedPorts = containerInfo != null
                    ? StringUtils.join(containerInfo.getPortMappings().toArray(), "/")
                    : "";

            LOGGER.fine(
                    "Offer not sufficient for slave request:\n" +
                            offer.getResourcesList().toString() +
                            "\n" + offer.getAttributesList().toString() +
                            "\nRequested for Jenkins slave:\n" +
                            "  cpus:  " + requestedCpus + "\n" +
                            "  mem:   " + requestedMem + "\n" +
                            "  ports: " + requestedPorts + "\n" +
                            "  attributes:  " + (slaveAttributes == null ? ""  : slaveAttributes.toString()));
            return false;
        }
    }

    /**
     * Checks whether the cloud Mesos slave attributes match those from the Mesos offer.
     *
     * @param offer Mesos offer data object.
     * @return true if all the offer attributes match and false if not.
     */
    private boolean slaveAttributesMatch(Protos.Offer offer, JSONObject slaveAttributes) {

        //Accept any and all Mesos slave offers by default.
        boolean slaveTypeMatch = true;

        //Collect the list of attributes from the offer as key-value pairs
        Map<String, String> attributesMap = new HashMap<>();
        for (Protos.Attribute attribute : offer.getAttributesList()) {
            attributesMap.put(attribute.getName(), attribute.getText().getValue());
        }

        if (slaveAttributes != null && slaveAttributes.size() > 0) {

            //Iterate over the cloud attributes to see if they exist in the offer attributes list.
            Iterator iterator = slaveAttributes.keys();
            while (iterator.hasNext()) {

                String key = (String) iterator.next();

                //If there is a single absent attribute then we should reject this offer.
                if (!(attributesMap.containsKey(key) && attributesMap.get(key).equals(slaveAttributes.getString(key)))) {
                    slaveTypeMatch = false;
                    break;
                }
            }
        }

        return slaveTypeMatch;
    }

    private void launchMesosTask(SchedulerDriver driver, Protos.Offer offer, Protos.TaskInfo taskInfo, Request request) {
        launchMesosTasks(driver, Collections.singletonList(offer.getId()), Collections.singletonMap(taskInfo, request), offer.getHostname());
    }

}
