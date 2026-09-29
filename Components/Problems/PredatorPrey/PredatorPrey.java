package predatorprey;

import java.util.List;

import core.AbstractSingleProblem;
import core.AbstractRepresentation;

/**
 * FREVO problem definition for the Predator-Prey simulation.
 * Extends AbstractSingleProblem — works with AbsoluteRanking and NoveltyRanking.
 *
 * Only prey brain is evolved. Predators move randomly (RandomController).
 * candidates[0] = prey brain
 *
 * Fitness = total alive-steps across all prey across 3 runs.
 * Higher = prey survived longer = better brain.
 *
 * Maximum fitness = maximumSteps × numberOfPrey × 3 runs
 * (if every prey survived every step of every run)
 */
public class PredatorPrey extends AbstractSingleProblem {

    private SimulationParameters parameters = new SimulationParameters();

    @Override
    protected double evaluateCandidate(AbstractRepresentation candidate) {

        parameters.initialize(getProperties(), getRandom());

        // Wrap single candidate in array — SimulationState uses candidates[0]
        // as the prey brain. Predators get RandomController internally.
        AbstractRepresentation[] candidates = new AbstractRepresentation[]{ candidate };

        SimulationState state = new SimulationState(parameters);
        state.setCandidates(candidates);
        state.setWithMonitor(false);
        state.setWithPause(false);

        SimulationServer server = new SimulationServer(parameters, state);
        server.runSimulation(null);

        // Prey fitness = total alive-steps (how long prey survived)
        double fitness = state.getFitness(1);
        System.out.println("[PredatorPrey] Prey fitness (alive-steps)=" + fitness);
        return fitness;
    }

    @Override
    public double getMaximumFitness() {
        // Theoretical maximum: all prey survive every step of every run
        // maximumSteps × numberOfPrey × RUNS_PER_EVALUATION(3)
        int maxSteps  = 500;  // matches maximumSteps property default
        int numPrey   = 20;   // matches numberOfPrey property default
        int runs      = 3;
        return maxSteps * numPrey * runs;
    }

    /** Runs the simulation with graphical display (FREVO replay button). */
    public void replayWithVisualization(AbstractRepresentation candidate) {
        parameters.initialize(getProperties(), getRandom());

        AbstractRepresentation[] candidates = new AbstractRepresentation[]{ candidate };

        SimulationState state = new SimulationState(parameters);
        state.setCandidates(candidates);

        new SimulationDisplay(parameters, state);
    }
}
