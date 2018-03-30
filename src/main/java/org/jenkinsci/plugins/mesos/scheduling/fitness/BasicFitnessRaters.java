package org.jenkinsci.plugins.mesos.scheduling.fitness;

import net.sf.json.JSONObject;
import org.apache.mesos.Protos;
import org.jenkinsci.plugins.mesos.config.slavedefinitions.MesosSlaveInfo;
import org.jenkinsci.plugins.mesos.scheduling.JenkinsSlave;
import org.jenkinsci.plugins.mesos.scheduling.Lease;
import org.jenkinsci.plugins.mesos.scheduling.Request;

import java.util.*;

final class BasicFitnessRaters {

    private BasicFitnessRaters() {}

    private final static FitnessRater CPU_ASSIGNABLE = new FitnessRater() {

        @Override
        public String toString() {
            return "CPU_ASSIGNABLE";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            JenkinsSlave.RequestJenkinsSlave requestedAgent = request.getRequest().getSlave();
            Set<String> requestedRoles = requestedAgent.getRoles();

            return rateScalarFitness(requestedAgent.getCpus(), lease.getAvailableCpus(requestedRoles));
        }
    };

    private final static FitnessRater MEM_ASSIGNABLE = new FitnessRater() {

        @Override
        public String toString() {
            return "MEM_ASSIGNABLE";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            JenkinsSlave.RequestJenkinsSlave requestedAgent = request.getRequest().getSlave();
            Set<String> requestedRoles = requestedAgent.getRoles();

            return rateScalarFitness(requestedAgent.getMem(), lease.getAvailableMem(requestedRoles));
        }
    };

    private final static FitnessRater PORTS_ASSIGNABLE = new FitnessRater() {

        @Override
        public String toString() {
            return "PORTS_ASSIGNABLE";
        }

        /**
         * Checks if there are enough port resources available.
         * <br />
         * For example:
         * <ul>
         *   <li>requested.hostPort = 8080, available.hostPorts.range = 8000 - 8900, 9000 - 9500 -> FITTEST</li>
         *   <li>requested.hostPort = 8080, available.hostPorts.range = 8000 - 8079, 8081 - 8900, 9000 - 9500 -> NOT_FIT</li>
         * </ul>
         *
         * @param requestedSize amount of the requested port mappings
         * @param availableSize amount of all available port resources
         * @return whether or not there are enough available port resources
         */
        private boolean areEnoughPortResourcesAvailable(int requestedSize, int availableSize) {
            return availableSize >= requestedSize;
        }

        private boolean isHostPortAssignable(long hostPort, List<Protos.Value.Range> availablePortResources) {
            for (Protos.Value.Range portRange : availablePortResources) {
                if (hostPort >= portRange.getBegin() && hostPort <= portRange.getEnd()) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Check if requested static configured host ports are in the range and thus, assignable.
         * <br />
         * For example:
         * <ul>
         *   <li>requested.hostPort = 8080, available.hostPorts.range = 8000 - 8900, 9000 - 9500 -> FITTEST</li>
         *   <li>requested.hostPort = 8080, available.hostPorts.range = 8000 - 8079, 8081 - 8900, 9000 - 9500 -> NOT_FIT</li>
         * </ul>
         * <br /><br />
         * Note: It is not necessary to check for double host entries, b/c PortMapping.equals() and Set<PortMapping>
         * should not allow that constellation.
         *
         * @param requestedPortMappings the requested port mappings with potential static configured host ports
         * @param availablePortResources all available port resources
         * @return whether or not all requested (static) host ports are assignable
         */
        private boolean areAllHostPortsAssignable(Set<MesosSlaveInfo.PortMapping> requestedPortMappings, List<Protos.Value.Range> availablePortResources) {
            for (MesosSlaveInfo.PortMapping requestedPortMapping : requestedPortMappings) {
                if (requestedPortMapping.isStaticHostPort()
                        && !isHostPortAssignable(requestedPortMapping.getHostPort(), availablePortResources)) {
                            return false;
                }
            }
            return true;
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            JenkinsSlave.RequestJenkinsSlave requestedAgent = request.getRequest().getSlave();
            Set<String> requestedRoles = requestedAgent.getRoles();
            Set<MesosSlaveInfo.PortMapping> requestedPortMappings = requestedAgent.getPortMappings();
            List<Protos.Value.Range> availablePortResources = lease.getAvailablePortResources(requestedRoles);

            return areEnoughPortResourcesAvailable(requestedPortMappings.size(), lease.getAvailablePortResourcesSize(requestedRoles)) &&
                    areAllHostPortsAssignable(requestedPortMappings, availablePortResources) ? FITTEST : NOT_FIT;
        }
    };

    private final static FitnessRater AVAILABILITY_ASSIGNABLE = new FitnessRater() {

        @Override
        public String toString() {
            return "AVAILABILITY_ASSIGNABLE";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            long estimatedDuration = request.getRequest().getSlave().getEstimatedDuration();
            return lease.isAvailable(new Date(), estimatedDuration) ? FITTEST : NOT_FIT;
        }
    };

    private final static FitnessRater ATTRIBUTES_ASSIGNABLE = new FitnessRater() {
        @Override
        public double rateFitness(Request request, Lease lease) {
            // TODO: use "JenkinsSlave.getAttributes()" and map instead of JSON object
            JSONObject slaveAttributes = request.getRequest().getSlaveInfo().getSlaveAttributes();
            return lease.hasAllAttributes(slaveAttributes) ? FITTEST : NOT_FIT;
        }
    };

    /**
     * Tests whether the lease can handle the request all.
     */
    public final static FitnessRater ASSIGNABLE = new FitnessRater() {

        @Override
        public String toString() {
            return "ASSIGNABLE";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            return combineFitnessValues(
                    AVAILABILITY_ASSIGNABLE.rateFitness(request, lease),
                    CPU_ASSIGNABLE.rateFitness(request, lease),
                    MEM_ASSIGNABLE.rateFitness(request, lease),
                    PORTS_ASSIGNABLE.rateFitness(request, lease),
                    ATTRIBUTES_ASSIGNABLE.rateFitness(request, lease)
            );
        }
    };

    private static double rateScalarFitness(double requestedValue, double availableValue) {
        return availableValue >= requestedValue ? FitnessRater.FITTEST : FitnessRater.NOT_FIT;
    }
}
