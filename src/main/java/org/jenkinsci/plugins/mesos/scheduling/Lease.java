package org.jenkinsci.plugins.mesos.scheduling;


import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.mesos.Protos;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Represents a Offer or Multiple Offers from a Mesos agent, acting as a "meta Offer" and provides additional
 * functionality to successfully assign a Task or multiple Tasks to the represented offers and keeping track of the
 * current available resource of the Offer.
 *
 */
public class Lease {

    private static final Logger LOGGER = Logger.getLogger(Lease.class.getName());

    private static final String CPUS_NAME = "cpus";
    private static final String MEM_NAME = "mem";
    private static final String PORTS_NAME = "ports";

    private final String id;
    private final String hostname;
    private final List<Protos.Offer> offers;
    private final Map<Protos.TaskInfo, Request> assignments;

    private final Map<String, Map<String, Double>> availableScalarResources;
    private final Map<String, Map<String, List<Protos.Value.Range>>> availableRangeResources;

    private final Map<String, String> availableAttributes;

    /**
     * Create a {@link Lease} out of multiple Offer objects.
     *
     * Note: Right now Mesos does not provide multiple offers per slave at once per framework (at least this seems true
     * for our allocator). Nevertheless, it is possible to provide multiple offers via the
     * {@link org.apache.mesos.SchedulerDriver#launchTasks(java.util.Collection, java.util.Collection)} thus providing this constructor for
     * possible future use.
     *
     * @param hostname the hostname of the Mesos agent
     * @param offers the offers the Mesos agent provides
     */
    private Lease(@Nonnull String hostname, @Nonnull Protos.Offer... offers) {
        this.id = generateId(offers);
        this.hostname = hostname;
        this.offers = Arrays.asList(offers);

        this.assignments = new LinkedHashMap<Protos.TaskInfo, Request>();

        this.availableScalarResources = new LinkedHashMap<String, Map<String, Double>>();
        this.availableRangeResources = new LinkedHashMap<String, Map<String, List<Protos.Value.Range>>>();
        initializeAvailableResources();

        this.availableAttributes = new LinkedHashMap<String, String>();
        initializeAttributes();
    }

    /**
     * Create a lease (meta offer) out ot an Offer object from a Mesos agent.
     *
     * @param offer the Offer
     */
    public Lease(@Nonnull Protos.Offer offer) {
        this(offer.getHostname(), offer);
    }

    private String generateId(Protos.Offer... offers) {
        // TODO: consider generating a hash sum when using multiple offers
        StringBuilder leaseId = new StringBuilder();

        for (Protos.Offer offer: offers) {
            leaseId.append(offer.getId().getValue());
        }

        return leaseId.toString();
    }

    private void initializeAttributes() {
        //Collect the list of attributes from the offer as key-value pairs
        for (Protos.Offer offer: offers) {
            for (Protos.Attribute attribute : offer.getAttributesList()) {
                availableAttributes.put(attribute.getName(), attribute.getText().getValue());
            }
        }
    }

    private void initializeAvailableResources() {
        for (Protos.Offer offer: offers) {
            for (Protos.Resource resource: offer.getResourcesList()) {
                switch(resource.getType()) {
                    case SCALAR:
                        addAvailableScalarResource(resource);
                        break;
                    case RANGES:
                        addAvailableRangeResource(resource);
                        break;
                    case SET:
                    case TEXT:
                    default:
                        LOGGER.warning("Unsupported resource type: " + resource.getType());
                        break;
                }
            }
        }
    }

    private boolean hasAttribute(String key, String value) {
        return availableAttributes.containsKey(key) && StringUtils.equals(availableAttributes.get(key), value);
    }

    public boolean hasAllAttributes(JSONObject requestedAttributes) {
        if (requestedAttributes != null && !requestedAttributes.isEmpty()) {
            //Iterate over the cloud attributes to see if they exist in the offer attributes list.
            Iterator iterator = requestedAttributes.keys();
            while (iterator.hasNext()) {
                String key = (String) iterator.next();
                String value = requestedAttributes.getString(key);

                //If there is a single absent attribute then we should reject this offer.
                if (!hasAttribute(key, value)) {
                    return false;
                }
            }
        }

        return true;
    }

    private void addAvailableScalarResource(Protos.Resource resource) {
        Map<String, Double> availableResources = availableScalarResources.get(resource.getName());
        if (availableResources == null) {
            availableResources = new HashMap<String, Double>();
        }

        Double availableValue = availableResources.get(resource.getRole());
        Double currentValue = resource.getScalar().getValue();
        if (availableValue == null) {
            availableValue = currentValue;
        } else {
            availableValue += currentValue;
        }

        availableResources.put(resource.getRole(), availableValue);
        availableScalarResources.put(resource.getName(), availableResources);
    }

    private void addAvailableRangeResource(Protos.Resource resource) {
        Map<String, List<Protos.Value.Range>> availableResources = availableRangeResources.get(resource.getName());
        if (availableResources == null) {
            availableResources = new HashMap<String, List<Protos.Value.Range>>();
        }

        List<Protos.Value.Range> availableRanges = availableResources.get(resource.getRole());

        List<Protos.Value.Range> currentRanges = resource.getRanges().getRangeList();
        if (availableRanges == null) {
            availableRanges = new ArrayList<Protos.Value.Range>(currentRanges);
        } else {
            availableRanges.addAll(currentRanges);
        }

        availableResources.put(resource.getRole(), availableRanges);
        availableRangeResources.put(resource.getName(), availableResources);
    }

    public List<Protos.Offer> getOffers() {
        return Collections.unmodifiableList(offers);
    }

    public List<Protos.OfferID> getOfferIds() {
        List<Protos.OfferID> offerIds = new ArrayList<Protos.OfferID>(offers.size());

        for (Protos.Offer offer : offers) {
            offerIds.add(offer.getId());
        }

        return Collections.unmodifiableList(offerIds);
    }

    public Map<Protos.TaskInfo, Request> getAssignments() {
        return Collections.unmodifiableMap(assignments);
    }

    public Collection<Request> getAssignedRequests() {
        return Collections.unmodifiableCollection(assignments.values());
    }

    public boolean hasAssignments() {
        return !getAssignments().isEmpty();
    }

    public String getHostname() {
        return hostname;
    }

    public String getId() {
        return id;
    }


    public List<Protos.Value.Range> getAvailableRangeResources(String name, Set<String> roles) {
        Map<String, List<Protos.Value.Range>> availableResources = availableRangeResources.get(name);

        List<Protos.Value.Range> result = new ArrayList<Protos.Value.Range>();
        if (availableResources != null) {
            for (String role : roles) {
                List<Protos.Value.Range> currentRange = availableResources.get(role);
                if (currentRange != null) {
                    result.addAll(currentRange);
                }
            }
        }

        return Collections.unmodifiableList(result);
    }

    public List<Protos.Value.Range> getTotalAvailableRangeResources(String name) {
        Set<String> roles = availableRangeResources.containsKey(name) ? availableRangeResources.get(name).keySet() : Collections.<String>emptySet();
        return getAvailableRangeResources(name, roles);
    }

    public List<Protos.Value.Range> getTotalAvailablePortResources() {
        return getTotalAvailableRangeResources(PORTS_NAME);
    }

    public List<Protos.Value.Range> getAvailablePortResources(String role) {
        return getAvailablePortResources(new LinkedHashSet<String>(Collections.singletonList(role)));
    }

    public List<Protos.Value.Range> getAvailablePortResources(Set<String> roles) {
        return getAvailableRangeResources(PORTS_NAME, roles);
    }

    public int getAvailablePortResourcesSize(Set<String> roles) {
        List<Protos.Value.Range> availablePortResources = getAvailablePortResources(roles);
        int availableSize = 0;
        for (Protos.Value.Range portRange : availablePortResources) {
            long currentSize = portRange.getEnd() - portRange.getBegin() + 1;
            availableSize += currentSize;
        }

        return availableSize;
    }


    public Double getAvailableScalarResources(String name, Set<String> roles) {
        Map<String, Double> availableResources = availableScalarResources.get(name);

        Double availableResourcesValue = 0.0;

        if (availableResources != null) {
            for (String role : roles) {
                Double currentValue = availableResources.get(role);
                if (currentValue != null) {
                    availableResourcesValue += currentValue;
                }
            }
        }

        return availableResourcesValue;
    }

    public Double getTotalAvailableScalarResources(String name) {
        Set<String> roles = availableScalarResources.containsKey(name) ? availableScalarResources.get(name).keySet() : Collections.<String>emptySet();
        return getAvailableScalarResources(name, roles);
    }

    public Double getTotalAvailableCpus() {
        return getTotalAvailableScalarResources(CPUS_NAME);
    }

    public Double getTotalAvailableMem() {
        return getTotalAvailableScalarResources(MEM_NAME);
    }

    public Double getAvailableCpus(String role) {
        return getAvailableCpus(new LinkedHashSet<String>(Collections.singletonList(role)));
    }

    public Double getAvailableCpus(Set<String> roles) {
        return getAvailableScalarResources(CPUS_NAME, roles);
    }

    public Double getAvailableMem(String role) {
        return getAvailableMem(new LinkedHashSet<String>(Collections.singletonList(role)));
    }

    public Double getAvailableMem(Set<String> roles) {
        return getAvailableScalarResources(MEM_NAME, roles);
    }


    private boolean isAssignable(Double newValue) {
        return !(newValue < 0.0);
    }

     /**
     * (Virtually) assign scalar resources to a temporary resources map which will be committed if all other resources
     * are assignable.
     *
     * @param name Name of the resource
     * @param role Role of the resource
     * @param requestedAmount Requested amount to assign to lease
     * @param tempAvailableScalarResources Temporary available resources to act assignments upon
     * @return whether or not the assignment was successful
     */
    private boolean assignRequestedScalarResource(String name, String role, Double requestedAmount, Map<String, Map<String, Double>> tempAvailableScalarResources) {
        if (!(tempAvailableScalarResources.containsKey(name) && tempAvailableScalarResources.get(name).containsKey(role))) {
            return requestedAmount > 0.0;
        }

        Map<String, Double> availableResources = tempAvailableScalarResources.get(name);
        Double availableValue = availableResources.get(role);

        Double newValue = availableValue - requestedAmount;

        boolean assignable = isAssignable(newValue);
        if (assignable) {
            availableResources.put(role, newValue);
        }
        return assignable;
    }

    /**
     * (Virtually) assign range resources to a temporary resources map which will be committed if all other resources
     * are assignable.
     *
     * @param name Name of the resource
     * @param role Role of the resource
     * @param requestedRanges Requested ranges as list
     * @param tempAvailableRangeResources Temporary resources to act assignments upon
     * @return whether or not the assignment was successful
     */
    private boolean assignRequestedRangeResources(String name, String role, List<Protos.Value.Range> requestedRanges, Map<String, Map<String, List<Protos.Value.Range>>> tempAvailableRangeResources) {
        if (!(tempAvailableRangeResources.containsKey(name) && tempAvailableRangeResources.get(name).containsKey(role))) {
            return requestedRanges.isEmpty();
        }

        Map<String, List<Protos.Value.Range>> availableResources = tempAvailableRangeResources.get(name);
        List<Protos.Value.Range> availableRanges = new ArrayList<Protos.Value.Range>(availableResources.get(role));

        Set<Long> unifiedAvailableRanges = getUnifiedSetOfRanges(availableRanges);
        Set<Long> unifiedRequestedRanges = getUnifiedSetOfRanges(requestedRanges);

        // validate:
        if (!unifiedAvailableRanges.containsAll(unifiedRequestedRanges)) {
            return false;
        }

        // assign
        unifiedAvailableRanges.removeAll(unifiedRequestedRanges);
        availableResources.put(role, createRangeList(new TreeSet<Long>(unifiedAvailableRanges)));
        return true;
    }

    private List<Protos.Value.Range> createRangeList(SortedSet<Long> sortedRanges) {
        ArrayList<Protos.Value.Range> resultRangeList = new ArrayList<Protos.Value.Range>();

        if (!sortedRanges.isEmpty()) {
            mutateRangeAndAddToListWhileRangesConnect(sortedRanges, resultRangeList, sortedRanges.first());
        }

        return resultRangeList;
    }

    private void mutateRangeAndAddToListWhileRangesConnect(SortedSet<Long> sortedRanges, List<Protos.Value.Range> resultRangeList, Long currentRange) {
        Protos.Value.Range.Builder rangeBuilder = Protos.Value.Range.newBuilder().setBegin(currentRange);

        while (sortedRanges.remove(currentRange)) {
            currentRange++;
        }
        rangeBuilder.setEnd(currentRange - 1);
        resultRangeList.add(rangeBuilder.build());

        if (!sortedRanges.isEmpty()) {
            mutateRangeAndAddToListWhileRangesConnect(sortedRanges, resultRangeList, sortedRanges.first());
        }
    }


    private Set<Long> getUnifiedSetOfRanges(List<Protos.Value.Range> listOfRanges) {
        Set<Long> setOfRanges = new HashSet<Long>();
        for (Protos.Value.Range range : listOfRanges) {
            for (long i = range.getBegin(); i <= range.getEnd(); i++) {
                setOfRanges.add(i);
            }
        }
        return setOfRanges;
    }


    private <K, V> Map<K, Map<K, V>> createTempMap(Map<K, Map<K, V>> source) {
        Map<K, Map<K, V>> target = new LinkedHashMap<K, Map<K, V>>();
        for (Map.Entry<K, Map<K, V>> entry : source.entrySet()) {
            target.put(entry.getKey(), new LinkedHashMap<K, V>(entry.getValue()));
        }
        return target;
    }

    public boolean assign(@Nonnull Request request, @Nonnull Protos.TaskInfo taskInfo) {
        boolean allAssigned = true;

        // "start transaction"
        Map<String, Map<String, Double>> tempAvailableScalarResources = createTempMap(availableScalarResources);
        Map<String, Map<String, List<Protos.Value.Range>>> tempAvailableRangeResources = createTempMap(availableRangeResources);

        for (Protos.Resource resource : taskInfo.getResourcesList()) {
            boolean assigned = true;
            switch (resource.getType()) {
                case SCALAR:
                    assigned = assignRequestedScalarResource(resource.getName(), resource.getRole(), resource.getScalar().getValue(), tempAvailableScalarResources);
                    break;
                case RANGES:
                    assigned = assignRequestedRangeResources(resource.getName(), resource.getRole(), resource.getRanges().getRangeList(), tempAvailableRangeResources);
                    break;
                case SET:
                case TEXT:
                default:
                    LOGGER.warning("Unable to assign unsupported resource type '" + resource.getType() + "' to Lease '" + id + "' for task '" + taskInfo.getTaskId().getValue() + "'");
                    break;
            }

            if (!assigned) {
                LOGGER.severe("Unable to assign resource type '" + resource.getType() + "' to Lease '" + id + "' for task '" + taskInfo.getTaskId().getValue() + "'");
                allAssigned &= assigned;
            }
        }

        if (allAssigned) {
            // "commit transaction"
            availableScalarResources.putAll(tempAvailableScalarResources);
            availableRangeResources.putAll(tempAvailableRangeResources);

            assignments.put(taskInfo, request);
        }

        return allAssigned;
    }

    public String toString() {
        StringBuilder roleCpusBuilder = new StringBuilder();
        if (availableScalarResources.containsKey(CPUS_NAME)) {
            for (String role : availableScalarResources.get(CPUS_NAME).keySet()) {
                roleCpusBuilder.append(String.format("cpus(%s): %.2f, ", role, getAvailableCpus(role)));
            }
        }

        StringBuilder roleMemBuilder = new StringBuilder();
        if (availableScalarResources.containsKey(MEM_NAME)) {
            for (String role : availableScalarResources.get(MEM_NAME).keySet()) {
                roleMemBuilder.append(String.format("mem(%s): %.2f, ", role, getAvailableMem(role)));
            }
        }

        return String.format("id: %s; hostname: %s; %stotalCpus: %.2f; %stotalMem: %.2f",
                id,
                hostname,
                roleCpusBuilder.toString(),
                getTotalAvailableCpus(),
                roleMemBuilder.toString(),
                getTotalAvailableMem());
    }

    public boolean isAvailableNow() {
        return isAvailable(new Date());
    }

    public boolean isAvailable(Date date) {
        return isAvailable(date, 0);
    }

    public boolean isAvailable(Date start, long duration) {
        Date end = new Date(start.getTime() + duration);

        boolean isAvailable = true;

        Iterator<Protos.Offer> iterator = offers.iterator();
        while (isAvailable && iterator.hasNext()) {
            Protos.Offer offer = iterator.next();
            if (offer.hasUnavailability()) {
                Protos.Unavailability unavailability = offer.getUnavailability();

                long unavailabilityDuration = unavailability.getDuration().getNanoseconds();
                Date unavailabilityStart = new Date(TimeUnit.NANOSECONDS.toMillis(unavailability.getStart().getNanoseconds()));
                Date unavailabilityEnd = new Date(unavailabilityStart.getTime() + TimeUnit.NANOSECONDS.toMillis(unavailabilityDuration));

                isAvailable = !(start.before(unavailabilityStart) && end.after(unavailabilityEnd))
                    && !(isDateBetweenRange(start, unavailabilityStart, unavailabilityEnd))
                    && !(isDateBetweenRange(end, unavailabilityStart, unavailabilityEnd));
            }
        }

        return isAvailable;
    }

    private boolean isDateBetweenRange(Date date, Date start, Date end) {
        return start.before(date) && end.after(date);
    }
}
