package predatorprey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import core.AbstractRepresentation;

/**
 * Holds the complete mutable state of one simulation run.
 * Mirrors the role of {@code PongState} in Pong:
 *   – owns all agents and the hex grid
 *   – tracks fitness / score accumulators
 *   – exposes reset / initialisation logic
 *
 * FREVO passes in the candidate representations via {@link #setCandidates};
 * the rest of the wiring is done internally.
 */
public class SimulationState {

    // ── FREVO candidates ──────────────────────────────────────────────────────
    //
    // Pong uses candidates[0] = team-0 brain, candidates[1] = team-1 brain.
    // Here we map:
    //   candidates[0] → shared predator brain
    //   candidates[1] → shared prey brain
    //
    private AbstractRepresentation[] candidates;

    /**
     * Set by PredatorPrey before placeAgents() runs, based on the
     * evovlePrey property. true = candidates[0] is the predator brain
     * being evolved (prey fixed/random); false = candidates[0] is the
     * prey brain being evolved (predator fixed/random).
     */
    private boolean predatorIsCandidate = false;

    // ── simulation objects ────────────────────────────────────────────────────
    private HexGrid        grid;
    private List<Agent>    predators = new ArrayList<>();
    private List<Agent>    prey      = new ArrayList<>();

    // ── timing ────────────────────────────────────────────────────────────────
    private int actualStep = 0;

    // ── fitness accumulators ──────────────────────────────────────────────────
    //   fitness[0] = predator fitness
    //   fitness[1] = prey fitness
    private double[] fitness = new double[2];

    // ── event counters (used for fitness computation) ─────────────────────────
    private int    catchCount         = 0;   // total raw catches this run
    /** Energy-weighted catch score: a catch at low predator energy scores near 1.0,
     *  a catch at near-full energy scores near 0.0. Sum of per-catch weights. */
    private double weightedCatchScore = 0.0;
    private int    preyStepsAlive     = 0;   // cumulative alive-steps for all prey

    // ── display flags (mirror PongState) ─────────────────────────────────────
    private boolean withPause   = false;

    private final SimulationParameters parameters;

    // ─────────────────────────────────────────────────────────────────────────

    public SimulationState(SimulationParameters parameters) {
        this.parameters = parameters;
    }

    // ── FREVO wiring ──────────────────────────────────────────────────────────

    public void setCandidates(AbstractRepresentation[] candidates) {
        this.candidates = candidates;
    }

    public AbstractRepresentation[] getCandidates() { return candidates; }

    public AbstractRepresentation getCandidate(int i) { return candidates[i]; }

    public void    setPredatorIsCandidate(boolean b) { predatorIsCandidate = b; }
    public boolean isPredatorIsCandidate()            { return predatorIsCandidate; }

    // ── reset / initialisation ────────────────────────────────────────────────

    /** Full reset called at the start of each repeated evaluation run. */
    public void resetScores() {
        actualStep         = 0;
        catchCount         = 0;
        weightedCatchScore = 0.0;
        preyStepsAlive     = 0;
        fitness[0]         = 0;
        fitness[1]         = 0;
    }

    /**
     * Rebuilds agents and the hex grid – called after {@link #resetScores()}.
     * Mirrors {@code PongState.resetSimulation()}.
     */
    public void resetSimulation() {
        // Give every cell a random starting food level within [foodInitMin, foodInitMax]
        int   fMin  = parameters.getFoodInitMin();
        int   fMax  = parameters.getFoodInitMax();
        int   range = fMax - fMin;
        grid = new HexGrid(parameters.getGridRadius(),
                           range > 0 ? fMin + parameters.getRandom().nextInt(range + 1) : fMin);

        // Seed food in clustered patches rather than uniform random scatter.
        // This creates realistic vegetation patterns that spread naturally via
        // the food growth mechanic, giving prey meaningful foraging decisions.
        seedFoodPatches(fMin, fMax);

        predators = new ArrayList<>();
        prey      = new ArrayList<>();
        placeAgents();
    }

    /**
     * Seeds food in a small number of rich patches rather than uniform
     * random scatter. Each patch centre starts at fMax; cells further
     * from any patch centre start at fMin and fill in naturally via the
     * spread mechanic over the first few simulation steps.
     */
    private void seedFoodPatches(int fMin, int fMax) {
        List<HexCell> allCells = new ArrayList<>(grid.getAllCells());
        if (allCells.isEmpty()) return;

        // Number of seed patches scales with grid size
        int numPatches = Math.max(3, parameters.getGridRadius() / 2);
        Collections.shuffle(allCells, parameters.getRandom());

        // All cells start bare
        for (HexCell cell : allCells) cell.setFood(fMin);

        // Place rich seed patches
        for (int p = 0; p < numPatches && p < allCells.size(); p++) {
            HexCell centre = allCells.get(p);
            centre.setFood(fMax);
            // Also fill immediate neighbours at half strength
            for (HexCell n : grid.getNeighbors(centre)) {
                n.setFood(fMax / 2);
            }
        }
    }

    /**
     * Randomly distributes agents on the hex grid.
     * Guarantees no two agents share a starting cell.
     *
     * Which side gets the evolving brain vs. a fixed one is decided by
     * predatorIsCandidate (set from the evovlePrey property):
     *   predatorIsCandidate == true  → candidates[0] = predator brain (evolving)
     *                                   candidates[1] = fixed prey brain (or null → random)
     *   predatorIsCandidate == false → candidates[0] = prey brain (evolving)
     *                                   candidates[1] = fixed predator brain (or null → random)
     */
    private void placeAgents() {
        // Shuffle all cell coordinates for random placement
        List<HexCell> cells = new ArrayList<>(grid.getAllCells());
        Collections.shuffle(cells, parameters.getRandom());

        int cellIdx = 0;

        for (int i = 0; i < parameters.getNumberOfPredators(); i++) {
            HexCell cell = cells.get(cellIdx++);
            Controller predCtrl;
            if (predatorIsCandidate) {
                // Evolving predators: candidates[0] = evolving predator brain
                predCtrl = new EvolvedController(candidates[0]);
            } else if (candidates.length >= 2 && candidates[1] != null) {
                // Evolving prey, with a fixed predator brain loaded from .zre
                predCtrl = new EvolvedController(candidates[1]);
            } else {
                // Evolving prey, no fixed predator brain given: predator moves randomly
                predCtrl = new RandomController(parameters.getRandom());
            }
            Agent a = new Agent(
                    Agent.Role.PREDATOR, i,
                    cell.getQ(), cell.getR(),
                    parameters.getAgentInitEnergy(),
                    predCtrl
            );
            predators.add(a);
            cell.setState(HexCell.CellState.PREDATOR);
        }

        for (int i = 0; i < parameters.getNumberOfPrey(); i++) {
            HexCell cell = cells.get(cellIdx++);
            Controller preyCtrl;
            if (predatorIsCandidate) {
                // Evolving predators: candidates[1] = fixed prey brain (loaded from zrePath),
                // or null if zrePath was left empty → prey fall back to random movement
                preyCtrl = candidates[1] != null
                        ? new EvolvedController(candidates[1])
                        : new RandomController(parameters.getRandom());
            } else {
                // Evolving prey: candidates[0] = evolving prey brain
                preyCtrl = new EvolvedController(candidates[0]);
            }
            Agent a = new Agent(
                    Agent.Role.PREY, i,
                    cell.getQ(), cell.getR(),
                    parameters.getAgentInitEnergy(),
                    preyCtrl
            );
            prey.add(a);
            cell.setState(HexCell.CellState.PREY);
        }
    }

    // ── step lifecycle ────────────────────────────────────────────────────────

    public boolean isPlaying() {
        return actualStep < parameters.getMaximumSteps()
                && hasLivingPrey()
                && hasLivingPredators();
    }

    private boolean hasLivingPrey()      { return prey.stream().anyMatch(Agent::isAlive); }
    private boolean hasLivingPredators() { return predators.stream().anyMatch(Agent::isAlive); }

    // ── event recording (called by SimulationServer) ──────────────────────────

    public void recordCatch() {
        catchCount++;
    }

    /**
     * Records an energy-weighted catch for predator fitness.
     * weight = 1 - (predator's energy at moment of catch / max energy),
     * clamped to [0,1]. A catch made while nearly out of energy scores
     * close to 1.0; a catch made at near-full energy scores close to 0.0.
     */
    public void recordWeightedCatch(double weight) {
        weightedCatchScore += Math.max(0.0, Math.min(1.0, weight));
    }

    public void recordPreyAliveStep() {
        preyStepsAlive++;
    }

    // ── fitness ───────────────────────────────────────────────────────────────

    public void setFitness(int role, double value) { fitness[role] = value; }
    public double getFitness(int role)             { return fitness[role]; }

    // ── accessors ─────────────────────────────────────────────────────────────

    public HexGrid        getGrid()      { return grid; }
    public List<Agent>    getPredators() { return predators; }
    public List<Agent>    getPrey()      { return prey; }
    public int            getActualStep(){ return actualStep; }
    public void           setActualStep(int s) { actualStep = s; }
    public int            getCatchCount()         { return catchCount; }
    public double         getWeightedCatchScore() { return weightedCatchScore; }
    public int            getPreyStepsAlive()     { return preyStepsAlive; }
    public boolean        isWithPause()        { return withPause; }
    public void           setWithPause(boolean b)  { withPause = b; }
    public SimulationParameters getParameters(){ return parameters; }
}
