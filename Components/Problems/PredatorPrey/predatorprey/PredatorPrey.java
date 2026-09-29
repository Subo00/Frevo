package predatorprey;

import java.io.File;

import core.AbstractRepresentation;
import core.AbstractSingleProblem;
import main.FrevoMain;

/**
 * FREVO problem definition — Predator-Prey.
 *
 * Evolves exactly ONE side per run (single population, per the
 * minimumCandidates/maximumCandidates = 1 requirement). Which side is
 * decided at runtime by the evovlePrey property:
 *
 *   evovlePrey = true   → evolves PREY.     Predator is fixed (from zrePath)
 *                         or random if zrePath is empty.
 *   evovlePrey = false  → evolves PREDATOR. Prey is fixed (from zrePath)
 *                         or random if zrePath is empty.
 *
 * ── Alternating co-evolution recipe ─────────────────────────────────────────
 *   Round 1 (evovlePrey=true,  zrePath="")           prey vs random predator
 *   Round 2 (evovlePrey=false, zrePath=<round1 .zre>) predator vs round-1 prey
 *   Round 3 (evovlePrey=true,  zrePath=<round2 .zre>) prey vs round-2 predator
 *   ... repeat as long as fitness keeps improving each round.
 *
 * After each round finishes, open the Results folder, find the saved .zre
 * with the highest fitness in its filename, copy its full path, and paste
 * it into zrePath before running the next round (also flip evovlePrey).
 *
 * Fitness:
 *   evolving prey      → total alive-steps (see SimulationServer)
 *   evolving predator  → energy-weighted catch score (see SimulationServer)
 */
public class PredatorPrey extends AbstractSingleProblem {

    private SimulationParameters parameters = new SimulationParameters();

    // Fixed opponent brain loaded once from zrePath (not reloaded every
    // evaluateCandidate call — that would hit disk on every fitness eval).
    private AbstractRepresentation fixedOpponentBrain = null;
    
    private static volatile AbstractRepresentation cachedOpponentBrain;
    private static volatile boolean opponentLoadAttempted = false;
    private static final Object OPPONENT_LOCK = new Object();

    @Override
    protected double evaluateCandidate(AbstractRepresentation candidate) {

        parameters.initialize(getProperties(), getRandom());
        loadFixedOpponentIfNeeded();

        boolean evolvingPrey = parameters.isEvolvePrey();

        // candidates[0] = the brain being evolved this run
        // candidates[1] = the fixed opponent brain (or null → random)
        AbstractRepresentation[] candidates =
                new AbstractRepresentation[]{ candidate, fixedOpponentBrain };

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
        loadFixedOpponentIfNeeded();

        boolean evolvingPrey = parameters.isEvolvePrey();

        AbstractRepresentation[] candidates =
                new AbstractRepresentation[]{ candidate, fixedOpponentBrain };

        SimulationState state = new SimulationState(parameters);
        state.setPredatorIsCandidate(!evolvingPrey);
        state.setCandidates(candidates);

        new SimulationDisplay(parameters, state);
    }

    private void loadFixedOpponentIfNeeded() {
    	if (opponentLoadAttempted) {
            fixedOpponentBrain = cachedOpponentBrain;
            return;
        }

        synchronized (OPPONENT_LOCK) {
            if (!opponentLoadAttempted) {
                String path = parameters.getZrePath();
                if (path == null || path.isEmpty()) {
                    System.out.println("[PredatorPrey] zrePath is empty — opponent will move randomly.");
                } else {
                    cachedOpponentBrain = loadBrain(path);
                }
                opponentLoadAttempted = true;
            }
        }

        fixedOpponentBrain = cachedOpponentBrain;
    }

    /**
     * Loads a fixed opponent brain (rank 0 = best individual) from a saved
     * .zre results file, using FREVO's own FrevoMain.getRepresentation(File,int)
     * utility — the same mechanism FREVO's command-line replay (-c) uses.
     *
     * NOTE: this depends on FrevoMain's internal API, which can differ
     * slightly between FREVO versions/forks. If it doesn't compile, check
     * the "main.FrevoMain" import against wherever FrevoMain actually lives
     * in your copy of FREVO's core sources. If it compiles but returns null,
     * double-check zrePath points at a real saved .zre with at least one
     * generation in it.
     */
    private AbstractRepresentation loadBrain(String path) {
        File f = new File(path);
        if (!f.exists()) {
            System.out.println("[PredatorPrey] ERROR: opponent brain file not found: " + path);
            return null;
        }
        AbstractRepresentation brain = null;
        try {
            brain = FrevoMain.getRepresentation(f, 0);
        } catch (Exception e) {
            System.out.println("[PredatorPrey] ERROR loading opponent brain: " + e);
        }
        if (brain == null) {
            System.out.println("[PredatorPrey] WARNING: opponent brain failed to load from "
                    + path + " — opponent will effectively move randomly!");
        } else {
            System.out.println("[PredatorPrey] Loaded fixed opponent brain from " + path);
        }
        return brain;
    }
}
