package org.jenkinsci.plugins.mesos.scheduling.fitness;

import org.jenkinsci.plugins.mesos.scheduling.Lease;
import org.jenkinsci.plugins.mesos.scheduling.Request;

import static org.jenkinsci.plugins.mesos.scheduling.fitness.PackingFitnessRaters.*;

public class SpreadingFitnessRaters {


    public static final FitnessRater MEM_SPREAD = new FitnessRater() {

        @Override
        public String toString() {
            return "MEM_SPREAD";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            double fitness = MEM_PACKING.rateFitness(request, lease);
            return fitness > NOT_FIT ? 1.0 - fitness : NOT_FIT;
        }

    };

    public static final FitnessRater CPU_SPREAD = new FitnessRater() {

        @Override
        public String toString() {
            return "CPU_SPREAD";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            double fitness = CPU_PACKING.rateFitness(request, lease);
            return fitness > NOT_FIT ? 1.0 - fitness : NOT_FIT;
        }

    };

    public static final FitnessRater CPU_MEM_SPREAD = new FitnessRater() {

        @Override
        public String toString() {
            return "CPU_MEM_SPREAD";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            double fitness = CPU_MEM_PACKING.rateFitness(request, lease);
            return fitness > NOT_FIT ? 1.0 - fitness : NOT_FIT;
        }

    };

}
