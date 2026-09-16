package predatorprey;

import core.AbstractRepresentation;
import core.AbstractSingleProblem;

/**
 * FREVO problem definition — Predator-Prey.
 *
 * ── Automatic in-run co-evolution ────────────────────────────────────────
 * A companion Method plugin (AlternatingCoevolution) drives both sides
 * automatically within a SINGLE run by handing this class a live opponent
 * object each generation, instead of a file path — see
 * setCoevolutionState(). This is now the ONLY supported mode; the old
 * file-based single-side round workflow has been removed.
 *
 * Fitness:
 *   evolving prey      → total alive-steps (see SimulationServer)
 *   evolving predator  → energy-weighted catch score (see SimulationServer)
 */
public class PredatorPrey extends AbstractSingleProblem {

    private SimulationParameters parameters = new SimulationParameters();

    // ── in-memory coevolution hooks (set by a Method plugin, not by XML) ───────
    public static volatile boolean               coevolutionModeActive  = false;
    public static volatile boolean               coevolutionEvolvingPrey = true;
    public static volatile AbstractRepresentation coevolutionOpponent   = null;

    public static void setCoevolutionState(boolean evolvingPrey, AbstractRepresentation opponent) {
        coevolutionModeActive  = true;
        coevolutionEvolvingPrey = evolvingPrey;
        coevolutionOpponent    = opponent;
    }

    /**
     * Public wrapper around evaluateCandidate(), so the companion
     * AlternatingCoevolution Method (different package) can score a
     * candidate directly, without going through FREVO's Ranking
     * abstraction. The Method must have already called setProperties(...)
     * on this instance before calling evaluate().
     */
    public double evaluate(AbstractRepresentation candidate) {
        return evaluateCandidate(candidate);
    }

    @Override
    protected double evaluateCandidate(AbstractRepresentation candidate) {

        parameters.initialize(getProperties(), getRandom());

        if (!coevolutionModeActive) {
            System.out.println("wrong mode selected (select AlternatingCoevolution)");
            return 0;
        }

        boolean evolvingPrey = coevolutionEvolvingPrey;
        AbstractRepresentation opponent = coevolutionOpponent;

        // candidates[0] = the brain being evolved this run
        // candidates[1] = the fixed opponent brain (or null → random)
        AbstractRepresentation[] candidates =
                new AbstractRepresentation[]{ candidate, opponent };

        SimulationState state = new SimulationState(parameters);
        // predatorIsCandidate tells SimulationState which role candidates[0] plays
        state.setPredatorIsCandidate(!evolvingPrey);
        state.setCandidates(candidates);
        state.setWithPause(false);

        SimulationServer server = new SimulationServer(parameters, state);
        server.runSimulation(null);

        // Prey fitness = alive-steps, predator fitness = energy-weighted catches
        double fitness = evolvingPrey ? state.getFitness(1) : state.getFitness(0);
        System.out.println("[PredatorPrey] " + (evolvingPrey ? "Prey" : "Predator")
                + " fitness=" + fitness);
        return fitness;
    }

    /** Returns the achievable maximum fitness of this problem. */
    public double getMaximumFitness() {
        return Double.MAX_VALUE;
    }

    /** Runs the simulation with graphical display (FREVO replay button). */
    public void replayWithVisualization(AbstractRepresentation candidate) {
        parameters.initialize(getProperties(), getRandom());

        // Use the same live coevolution state evaluateCandidate() uses —
        // these static fields hold whatever the last generation of the run
        // was configured with (which side was evolving, and its frozen
        // opponent). The previous version of this method used dead code
        // (fixedOpponentBrain/cachedOpponentBrain, where cachedOpponentBrain
        // was declared but never assigned anywhere) — that always produced
        // a null opponent here, which caused the
        // "IndexOutOfBoundsException: Index 1 out of bounds for length 1"
        // crash inside SimulationDisplay when replaying.
        boolean evolvingPrey = coevolutionEvolvingPrey;
        AbstractRepresentation opponent = coevolutionOpponent;

        AbstractRepresentation[] candidates =
                new AbstractRepresentation[]{ candidate, opponent };

        SimulationState state = new SimulationState(parameters);
        state.setPredatorIsCandidate(!evolvingPrey);
        state.setCandidates(candidates);

        new SimulationDisplay(parameters, state);
    }
}