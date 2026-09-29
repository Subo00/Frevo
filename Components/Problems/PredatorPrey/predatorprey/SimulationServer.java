package predatorprey;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

import core.AbstractRepresentation;
import predatorprey.HexCell.CellState;
import core.AbstractMultiProblem.RepresentationWithScore;

/**
 * Runs the predator-prey simulation loop and computes fitness scores.
 * Mirrors {@code PongServer} exactly in structure:
 *   – {@link #runSimulation(JPanel)} is the main entry point
 *   – {@link #getResults()} returns FREVO-compatible {@link RepresentationWithScore} list
 *   – {@link #stop()} allows the display thread to interrupt the loop
 *
 * The simulation is repeated {@value #RUNS_PER_EVALUATION} times (same as
 * Pong's 3-round structure) and fitness is accumulated across runs to reduce
 * the effect of random initial placement.
 *
 * ── Fitness design ──────────────────────────────────────────────────────────
 *   Predator fitness  = energy-weighted catch score (higher → better hunters;
 *                       a catch made while low on energy scores more than one
 *                       made at near-full energy — see resolveCatches())
 *   Prey fitness      = total alive-steps summed over all prey agents
 *                       (higher → better survivors)
 *
 * Only one side is ever evolved per run, chosen by the evovlePrey property
 * (see PredatorPrey.java / SimulationParameters).
 */
public class SimulationServer {

    /** Number of repeated evaluations per FREVO fitness call (mirrors Pong's 3). */
    private static final int RUNS_PER_EVALUATION = 3;

    /** Reproduction accumulator thresholds — lower = more frequent births. */
    private static final double PRED_REPRO_THRESHOLD = 80.0;
    private static final double PREY_REPRO_THRESHOLD = 30.0;
    private static final double REPRO_ENERGY_RATE    = 0.08;
    private static final int    MAX_PREDATORS        = 20;
    private static final int    MAX_PREY             = 60;

    private final SimulationParameters parameters;
    private final SimulationState      state;
    private volatile boolean           isInterrupted = false;

    // cumulative across all runs
    private int    totalCatches        = 0;
    private double totalWeightedCatches = 0.0;
    private int    totalPreyAlive      = 0;

    public SimulationServer(SimulationParameters parameters, SimulationState state) {
        this.parameters = parameters;
        this.state      = state;
    }

    // ── main simulation entry point (mirrors PongServer.runSimulation) ────────

    /**
     * @param display  if non-null the panel is repainted each step (replay mode);
     *                 if null the simulation runs headless at full speed.
     */
    public void runSimulation(JPanel display) {
        totalCatches         = 0;
        totalWeightedCatches = 0.0;
        totalPreyAlive       = 0;

        for (int run = 0; run < RUNS_PER_EVALUATION; run++) {

            state.resetScores();
            state.resetSimulation();

            int step = 0;
            while (state.isPlaying()) {
                if (isInterrupted) break;

                state.setActualStep(step);

                // 1. Calculate sensors for all living agents
                for (Agent a : state.getPredators()) {
                    if (a.isAlive()) calculateSensors(a);
                }
                for (Agent a : state.getPrey()) {
                    if (a.isAlive()) calculateSensors(a);
                }

                // 2. Ask each controller to decide (fills intendedMove)
                for (Agent a : state.getPredators()) {
                    if (a.isAlive()) {
                        if (parameters.isDebugging()) {
                            a.getController().setParameters(parameters);
                            a.getController().setState(state);
                        }
                        a.process();
                    }
                }
                for (Agent a : state.getPrey()) {
                    if (a.isAlive()) {
                        if (parameters.isDebugging()) {
                            a.getController().setParameters(parameters);
                            a.getController().setState(state);
                        }
                        a.process();
                    }
                }

                // 3. Apply movement — eating prey stay put, so handle eat-action first
                resolveHerbivoreEating();   // eat before move so movers don't collide with eaters
                applyMovement(state.getPredators());
                applyMovement(state.getPrey());

                // 4. Apply energy decay
                applyEnergyDecay();

                // 6. Resolve predator–prey interactions (catches)
                resolveCatches();

                // 7. Reproduce
                resolveReproduction();

                // 8. Grow food on every cell (after agents have eaten)
                state.getGrid().tickFoodGrowth();

                // 9. Record per-step statistics
                for (Agent a : state.getPrey()) {
                    if (a.isAlive()) state.recordPreyAliveStep();
                }

                // 7. Optionally update display
                if (display != null) {
                    display.repaint();
                    pause(50);
                }

                step++;
            }

            // Accumulate across runs
            totalCatches         += state.getCatchCount();
            totalWeightedCatches += state.getWeightedCatchScore();
            totalPreyAlive       += state.getPreyStepsAlive();
            
            long alivePred = state.getPredators().stream().filter(Agent::isAlive).count();
            long alivePrey = state.getPrey().stream().filter(Agent::isAlive).count();
            System.out.println("Run " + (run+1) 
                + " | Steps: " + step 
                + " | Predators alive: " + alivePred 
                + " | Prey alive: " + alivePrey
                + " | Catches: " + state.getCatchCount());
        }

        // Compute final fitness values
        // Predator fitness = energy-weighted catch score (not raw count):
        // catches made while low on energy are worth more.
        state.setFitness(0, totalWeightedCatches);    // predator fitness
        state.setFitness(1, totalPreyAlive);          // prey fitness
        System.out.println("[PredatorPrey] Fitness — predator(weighted catches)=" + totalWeightedCatches
                + " (raw catches=" + totalCatches + ")"
                + " prey(alive-steps)=" + totalPreyAlive);
    }

    // ── sensor calculation (mirrors PongServer.calculateSensors) ─────────────

    private void calculateSensors(Agent agent) {
        calculateSelfSensor(agent);
        calculateNearestOpponent(agent);
        calculateNearestAlly(agent);
        calculateFoodSensor(agent);   // food on current cell (prey only; 0 for predators)
    }

    /**
     * Own position (normalised to grid diameter) and energy level.
     * Sensor indices [0..2].
     */
    private void calculateSelfSensor(Agent agent) {
        List<Float> self = new ArrayList<>();
        int diam = parameters.getGridRadius() * 2;
        self.add((float)(agent.getQ() + parameters.getGridRadius()) / diam);
        self.add((float)(agent.getR() + parameters.getGridRadius()) / diam);
        double maxEnergy = parameters.getAgentInitEnergy();
        self.add((float)(agent.getEnergy() / maxEnergy));
        agent.setSensorSelf(self);
    }

    /**
     * Direction and normalised distance to the nearest living agent
     * of the opposite type (predator→prey, prey→predator).
     * Sensor indices [3..5].
     */
    private void calculateNearestOpponent(Agent agent) {
        List<Agent> opponents = agent.getRole() == Agent.Role.PREDATOR
                ? state.getPrey() : state.getPredators();

        List<Float> sensor = new ArrayList<>();
        Agent nearest = findNearest(agent, opponents);
        int diam = parameters.getGridRadius() * 2;

        if (nearest == null) {
            // No living opponent left – signal "far away"
            sensor.add(0f);
            sensor.add(0f);
            sensor.add(1f);
        } else {
            float dq   = (float)(nearest.getQ() - agent.getQ()) / diam;
            float dr   = (float)(nearest.getR() - agent.getR()) / diam;
            float dist = (float) axialDistance(agent, nearest) / (parameters.getGridRadius() * 2);
            sensor.add(dq);
            sensor.add(dr);
            sensor.add(dist);
        }
        agent.setSensorNearest(sensor);
    }

    /**
     * Direction and normalised distance to the nearest living ally.
     * Sensor indices [6..7].
     */
    private void calculateNearestAlly(Agent agent) {
        List<Agent> allies = agent.getRole() == Agent.Role.PREDATOR
                ? state.getPredators() : state.getPrey();

        List<Float> sensor = new ArrayList<>();
        Agent nearest = findNearest(agent, allies, agent);   // exclude self
        int diam = parameters.getGridRadius() * 2;

        if (nearest == null) {
            sensor.add(0f);
            sensor.add(0f);
        } else {
            sensor.add((float)(nearest.getQ() - agent.getQ()) / diam);
            sensor.add((float)(nearest.getR() - agent.getR()) / diam);
        }
        agent.setSensorNeighbor(sensor);
    }

    /**
     * Food level on the agent's current cell, normalised to [0, 1].
     * Predators always receive 0 — they have no use for this sensor.
     * Sensor index [8].
     */
    private void calculateFoodSensor(Agent agent) {
        List<Float> sensor = new ArrayList<>();
        if (agent.getRole() == Agent.Role.PREY) {
            HexCell cell = state.getGrid().getCell(agent.getQ(), agent.getR());
            float normalised = (cell != null)
                    ? (float) cell.getFood() / HexCell.FOOD_MAX
                    : 0f;
            sensor.add(normalised);
        } else {
            sensor.add(0f);   // predators ignore food
        }
        agent.setSensorFood(sensor);
    }

    // ── herbivore eating ──────────────────────────────────────────────────────

    /**
     * Resolves the eat action for all prey that chose {@code STAY_AND_EAT}.
     *
     * A prey agent that eats this turn:
     *   – does NOT move (intendedMove is reset to STAY so applyMovement skips it)
     *   – consumes {@code foodEatAmount} food from its current cell
     *   – gains {@code foodEnergyGain} energy per food unit actually consumed
     *     (may be less than requested if the cell has little food)
     *
     * Called before applyMovement so eating agents are treated as stationary.
     */
    private void resolveHerbivoreEating() {
        for (Agent prey : state.getPrey()) {
            if (!prey.isAlive()) continue;
            if (prey.getIntendedMove() != Agent.STAY_AND_EAT) continue;

            HexCell cell = state.getGrid().getCell(prey.getQ(), prey.getR());
            if (cell == null) continue;

            int consumed = cell.consumeFood(parameters.getFoodEatAmount());
            prey.addEnergy(consumed * parameters.getFoodEnergyGain());

            // Clear the eat-action so applyMovement treats this agent as staying
            prey.setIntendedMove(Agent.STAY);
        }
    }

    // ── movement (mirrors Paddle.update / ball.update) ────────────────────────

    private void applyMovement(List<Agent> agents) {
        for (Agent agent : agents) {
            if (!agent.isAlive()) continue;

            int dir = agent.getIntendedMove();
            if (dir < 0 || dir > 5) continue;   // stay

            int[] d   = HexCell.DIRECTIONS[dir];
            int   nq  = agent.getQ() + d[0];
            int   nr  = agent.getR() + d[1];

            // Only move if the target cell exists in the grid
            if (state.getGrid().inBounds(nq, nr)) {
            	 // and if it isn't taken
            	if(state.getGrid().getCell(nq, nr).getState() == CellState.EMPTY)
            	{	
	                // Update cell states
	                state.getGrid().getCell(agent.getQ(), agent.getR())
	                        .setState(HexCell.CellState.EMPTY);
	                agent.moveTo(nq, nr);
	                state.getGrid().getCell(nq, nr)
	                        .setState(agent.getRole() == Agent.Role.PREDATOR
	                                ? HexCell.CellState.PREDATOR
	                                : HexCell.CellState.PREY);
            	}
            }
        }
    }

    // ── energy decay ──────────────────────────────────────────────────────────

    private void applyEnergyDecay() {
        for (Agent a : state.getPredators()) {
            if (!a.isAlive()) continue;
            a.addEnergy(-parameters.getEnergyDecayPredator());
            if (a.getEnergy() <= 0) {
                a.die();
                state.getGrid().getCell(a.getQ(), a.getR())
                        .setState(HexCell.CellState.EMPTY);
            }
        }
        for (Agent a : state.getPrey()) {
            if (!a.isAlive()) continue;
            a.addEnergy(-parameters.getEnergyDecayPrey());
            if (a.getEnergy() <= 0) {
                a.die();
                state.getGrid().getCell(a.getQ(), a.getR())
                        .setState(HexCell.CellState.EMPTY);
            }
        }
    }

    

    private void killAgent(Agent a) {
        a.die();
        HexCell cell = state.getGrid().getCell(a.getQ(), a.getR());
        if (cell != null) cell.setState(HexCell.CellState.EMPTY);
    }

    // ── reproduction ──────────────────────────────────────────────────────────

    private void resolveReproduction() {
        java.util.List<Agent> newPred = new java.util.ArrayList<>();
        java.util.List<Agent> newPrey = new java.util.ArrayList<>();

        for (Agent a : state.getPredators()) {
            if (!a.isAlive()) continue;
            if (state.getPredators().size() + newPred.size() >= MAX_PREDATORS) break;
            if (accumulate(a, PRED_REPRO_THRESHOLD)) {
                Agent child = spawnOffspring(a, Agent.Role.PREDATOR,
                        state.getPredators().size() + newPred.size());
                if (child != null) newPred.add(child);
            }
        }

        for (Agent a : state.getPrey()) {
            if (!a.isAlive()) continue;
            if (state.getPrey().size() + newPrey.size() >= MAX_PREY) break;
            if (accumulate(a, PREY_REPRO_THRESHOLD)) {
                Agent child = spawnOffspring(a, Agent.Role.PREY,
                        state.getPrey().size() + newPrey.size());
                if (child != null) newPrey.add(child);
            }
        }

        state.getPredators().addAll(newPred);
        state.getPrey().addAll(newPrey);
    }

    private boolean accumulate(Agent a, double threshold) {
        double maxE = parameters.getAgentInitEnergy();
        a.addReproductionEnergy((a.getEnergy() / maxE) * REPRO_ENERGY_RATE * threshold);
        if (a.getReproductionAccumulator() >= threshold) {
            a.resetReproductionAccumulator();
            return true;
        }
        return false;
    }

    private Agent spawnOffspring(Agent parent, Agent.Role role, int id) {
        HexCell parentCell = state.getGrid().getCell(parent.getQ(), parent.getR());
        if (parentCell == null) return null;

        java.util.List<HexCell> empty = new java.util.ArrayList<>();
        for (HexCell n : state.getGrid().getNeighbors(parentCell))
            if (n.getState() == HexCell.CellState.EMPTY) empty.add(n);
        if (empty.isEmpty()) return null;

        java.util.Collections.shuffle(empty, parameters.getRandom());
        HexCell bc = empty.get(0);

        // Offspring controller mirrors placeAgents() logic — same
        // predatorIsCandidate flag decides which side is evolving.
        Controller childController;
        AbstractRepresentation[] cands = state.getCandidates();
        boolean predatorIsCandidate = state.isPredatorIsCandidate();
        if (role == Agent.Role.PREDATOR) {
            if (predatorIsCandidate) {
                childController = new EvolvedController(cands[0]);
            } else if (cands.length >= 2 && cands[1] != null) {
                childController = new EvolvedController(cands[1]);
            } else {
                childController = new RandomController(parameters.getRandom());
            }
        } else {
            if (predatorIsCandidate) {
                childController = cands[1] != null
                        ? new EvolvedController(cands[1])
                        : new RandomController(parameters.getRandom());
            } else {
                childController = new EvolvedController(cands[0]);
            }
        }
        Agent child = new Agent(role, id, bc.getQ(), bc.getR(),
                parent.getEnergy() * 0.5,
                childController);
        bc.setState(role == Agent.Role.PREDATOR
                ? HexCell.CellState.PREDATOR : HexCell.CellState.PREY);
        return child;
    }

    // ── catch resolution ──────────────────────────────────────────────────────

    /**
     * For each predator, check whether any living prey is within
     * {@code catchRadius} axial distance. First prey found is caught.
     */
    private void resolveCatches() {
        for (Agent pred : state.getPredators()) {
            if (!pred.isAlive()) continue;
            for (Agent p : state.getPrey()) {
                if (!p.isAlive()) continue;
                if (axialDistance(pred, p) <= 1) {
                    // Catch! Weight the score by how depleted the predator's
                    // energy was at the moment of the catch — catching prey
                    // while nearly out of energy is worth more than catching
                    // it at near-full energy.
                    double maxEnergy = parameters.getAgentInitEnergy();
                    double weight = 1.0 - (pred.getEnergy() / maxEnergy);
                    state.recordWeightedCatch(weight);

                    p.die();
                    state.getGrid().getCell(p.getQ(), p.getR())
                            .setState(HexCell.CellState.EMPTY);
                    pred.addEnergy(parameters.getEnergyGainCatch());
                    state.recordCatch();
                    break;   // one catch per predator per step
                }
            }
        }
    }

    // ── fitness results (mirrors PongServer.getResults) ───────────────────────

    /**
     * Returns results ordered best-first, as required by
     * {@code AbstractMultiProblem.evaluateFitness}.
     */
    /**
     * Returns the single prey fitness score.
     * Fitness = total alive-steps across all prey across all runs.
     * Higher = prey survived longer = better brain.
     */
    public List<RepresentationWithScore> getResults() {
        List<RepresentationWithScore> results = new ArrayList<>();
        double preyFitness = state.getFitness(1);
        results.add(new RepresentationWithScore(state.getCandidate(0), preyFitness));
        return results;
    }

    // ── control ───────────────────────────────────────────────────────────────

    public void stop() { isInterrupted = true; }

    // ── helpers ───────────────────────────────────────────────────────────────

    private int axialDistance(Agent a, Agent b) {
        return (Math.abs(a.getQ() - b.getQ())
              + Math.abs(a.getQ() + a.getR() - b.getQ() - b.getR())
              + Math.abs(a.getR() - b.getR())) / 2;
    }

    private Agent findNearest(Agent self, List<Agent> pool) {
        return findNearest(self, pool, null);
    }

    private Agent findNearest(Agent self, List<Agent> pool, Agent exclude) {
        Agent best = null;
        int   bestDist = Integer.MAX_VALUE;
        for (Agent a : pool) {
            if (!a.isAlive() || a == exclude) continue;
            int d = axialDistance(self, a);
            if (d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }

    protected synchronized void pause(int ms) {
        try { this.wait(ms); } catch (InterruptedException ignored) {}
    }
}