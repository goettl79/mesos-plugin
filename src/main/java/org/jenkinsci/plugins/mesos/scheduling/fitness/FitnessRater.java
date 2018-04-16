package org.jenkinsci.plugins.mesos.scheduling.fitness;

import org.jenkinsci.plugins.mesos.scheduling.Lease;
import org.jenkinsci.plugins.mesos.scheduling.Request;

public abstract class FitnessRater {

    public static final double NOT_FIT   = -1.0;
    public static final double FITTEST   =  1.0;
    public static final double UNFITTEST =  0.0;


    /**
     * Calculates the average of all provided fitness values or returns {@link #NOT_FIT} if one of the
     * provided values is less than {@value UNFITTEST}
     *
     * @param fitnessValues the fitness values to rate
     * @return average of the values or {@link #NOT_FIT} ({@value NOT_FIT})
     */
    protected double combineFitnessValues(double... fitnessValues) {
        if (fitnessValues == null) {
            throw new IllegalArgumentException("Please specify valid fitness values");
        }

        double sum = UNFITTEST;
        for (double value : fitnessValues) {
            if (value < UNFITTEST) {
                return NOT_FIT;
            }
            sum += value;
        }

        return sum / fitnessValues.length;
    }

    /**
     * Rates how fit a request is to run on the provided lease.
     *
     * @param request the request containing the required resources
     * @param lease the potential lease (offer) where the provided request
     * @return a value between {@value UNFITTEST} ({@link #UNFITTEST}) and {@value FITTEST} ({@link #FITTEST}),
     *         or {@value NOT_FIT} ({@link #NOT_FIT}), where higher values represent better fitness and {@value NOT_FIT}
     *         representing the lease cannot run the request at all (due to lack of resources or the request not meeting
     *         other requirements).
     */
    public abstract double rateFitness(Request request, Lease lease);

}
