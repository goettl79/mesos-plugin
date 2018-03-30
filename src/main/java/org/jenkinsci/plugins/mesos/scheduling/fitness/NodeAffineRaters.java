package org.jenkinsci.plugins.mesos.scheduling.fitness;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.mesos.scheduling.Lease;
import org.jenkinsci.plugins.mesos.scheduling.Request;

import static org.jenkinsci.plugins.mesos.scheduling.fitness.FitnessRater.FITTEST;
import static org.jenkinsci.plugins.mesos.scheduling.fitness.FitnessRater.NOT_FIT;
import static org.jenkinsci.plugins.mesos.scheduling.fitness.PackingFitnessRaters.CPU_MEM_PACKING;
import static org.jenkinsci.plugins.mesos.scheduling.fitness.SpreadingFitnessRaters.CPU_MEM_SPREAD;

public class NodeAffineRaters {

    public static final FitnessRater NODE_AFFINE_CPU_MEM_PACKING = new FitnessRater() {

        @Override
        public String toString() {
            return "NODE_AFFINE_CPU_MEM_PACKING";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            return rateAffineFitness(request, lease, CPU_MEM_PACKING);
        }

    };

    public static final FitnessRater NODE_AFFINE_CPU_MEM_SPREAD = new FitnessRater() {

        @Override
        public String toString() {
            return "NODE_AFFINE_CPU_MEM_SPREAD";
        }

        @Override
        public double rateFitness(Request request, Lease lease) {
            return rateAffineFitness(request, lease, CPU_MEM_SPREAD);
        }

    };


    private static final double PENALTY_VALUE = 0.1;

    private static double rateAffineFitness(Request request, Lease lease, FitnessRater otherRater) {
        String lastBuildHostname = request.getRequest().getSlave().getLastBuildHostname();
        String leaseHostname = lease.getHostname();

        double affinityFitness = StringUtils.equals(leaseHostname, lastBuildHostname) ? FITTEST : NOT_FIT;
        double otherFitness = getPenalizedFitness(otherRater.rateFitness(request, lease), affinityFitness);

        return otherFitness > NOT_FIT && affinityFitness > NOT_FIT ? affinityFitness : otherFitness;
    }

    private static double getPenalizedFitness(double otherFitness, double affinityFitness) {
        if (affinityFitness > NOT_FIT && otherFitness == FITTEST) {
            otherFitness -= PENALTY_VALUE;
        }

        return otherFitness;
    }

}
