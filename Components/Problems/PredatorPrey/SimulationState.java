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
    // Only prey are evolved — candidates[0] is always the evolving prey
    // brain. Predators always move randomly (RandomController).
    //
    private AbstractRepresentation[] candidates;

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
    private int catchCount      = 0;   // total catches this run
    private int preyStepsAlive  = 0;   // cumulative alive-steps for all prey

    // ── display flags (mirror PongState) ─────────────────────────────────────
    private boolean withMonitor = false;
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

    // ── reset / initialisation ────────────────────────────────────────────────

    /** Full reset called at the start of each repeated evaluation run. */
    public void resetScores() {
        actualStep     = 0;
        catchCount     = 0;
        preyStepsAlive = 0;
        fitness[0]     = 0;
        fitness[1]     = 0;
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

        // Randomise individual cell food after construction so each cell differs
        if (range > 0) {
            for (HexCell cell : grid.getAllCells()) {
                cell.setFood(fMin + parameters.getRandom().nextInt(range + 1));
            }
        }

        predators = new ArrayList<>();
        prey      = new ArrayList<>();
        placeAgents();
    }

    /**
     * Randomly distributes agents on the hex grid.
     * Guarantees no two agents share a starting cell.
     */
    private void placeAgents() {
        // Shuffle all cell coordinates for random placement
        List<HexCell> cells = new ArrayList<>(grid.getAllCells());
        Collections.shuffle(cells, parameters.getRandom());

        int cellIdx = 0;

        for (int i = 0; i < parameters.getNumberOfPredators(); i++) {
            HexCell cell = cells.get(cellIdx++);
            Agent a = new Agent(
                    Agent.Role.PREDATOR, i,
                    cell.getQ(), cell.getR(),
                    parameters.getPredatorInitEnergy(),
                    new RandomController(parameters.getRandom())
            );
            predators.add(a);
            cell.setState(HexCell.CellState.PREDATOR);
        }

        for (int i = 0; i < parameters.getNumberOfPrey(); i++) {
            HexCell cell = cells.get(cellIdx++);
            Agent a = new Agent(
                    Agent.Role.PREY, i,
                    cell.getQ(), cell.getR(),
                    parameters.getPreyInitEnergy(),
                    new EvolvedController(candidates[0])
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
    public int            getCatchCount()      { return catchCount; }
    public int            getPreyStepsAlive()  { return preyStepsAlive; }
    public boolean        isWithMonitor()      { return withMonitor; }
    public void           setWithMonitor(boolean b){ withMonitor = b; }
    public boolean        isWithPause()        { return withPause; }
    public void           setWithPause(boolean b)  { withPause = b; }
    public SimulationParameters getParameters(){ return parameters; }
}
