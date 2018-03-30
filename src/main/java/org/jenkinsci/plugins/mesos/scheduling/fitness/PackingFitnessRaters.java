package org.jenkinsci.plugins.mesos.scheduling.fitness;

import org.jenkinsci.plugins.mesos.scheduling.Lease;
import org.jenkinsci.plugins.mesos.scheduling.Request;

import static org.jenkinsci.plugins.mesos.scheduling.fitness.BasicFitnessRaters.ASSIGNABLE;

public final class PackingFitnessRaters {

    private PackingFitnessRaters() {}

    public static final FitnessRater CPU_PACKING = new FitnessRater() {

        @Override
        public String toString() {
            return "CPU_PACKING";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            double totalAvailableCpus = lease.getTotalAvailableCpus();
            double requestedCpus = request.getRequest().getSlave().getCpus();

            double assignableFitness = ASSIGNABLE.rateFitness(request, lease);

            return totalAvailableCpus > 0.0 && assignableFitness > NOT_FIT ? requestedCpus / totalAvailableCpus : NOT_FIT;
        }

    };

    public static final FitnessRater MEM_PACKING = new FitnessRater() {

        @Override
        public String toString() {
            return "MEM_PACKING";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            double totalAvailableMem = lease.getTotalAvailableMem();
            double requestedMem = request.getRequest().getSlave().getMem();

            double assignableFitness = ASSIGNABLE.rateFitness(request, lease);

            return totalAvailableMem > 0.0 && assignableFitness > NOT_FIT ? requestedMem / totalAvailableMem : NOT_FIT;
        }

    };

    public static final FitnessRater CPU_MEM_PACKING = new FitnessRater() {

        @Override
        public String toString() {
            return "CPU_MEM_PACKING";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            return combineFitnessValues(
                    CPU_PACKING.rateFitness(request, lease),
                    MEM_PACKING.rateFitness(request, lease)
            );
        }

    };

}
