package predatorprey;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single agent (predator or prey) living on the hex grid.
 *
 * Mirrors the role of {@code Paddle} in Pong: it carries a {@link Controller},
 * exposes sensor data, and accepts movement decisions produced by that controller.
 *
 * Sensor layout (all values normalised to [0, 1] or [-1, 1]):
 *   sensorSelf     – the agent's own position / energy level
 */
public class Agent {

    /** Which role this agent plays in the simulation. */
    public enum Role { PREDATOR, PREY }

    // ── identity ─────────────────────────────────────────────────────────────
    private final Role role;
    private final int  id;          // index within its role-group

    // ── position on the hex grid (axial coordinates) ─────────────────────────
    private int q;
    private int r;

    // ── energy / alive state ──────────────────────────────────────────────────
    // Death is now driven purely by energy reaching zero.
    private double  energy;
    private boolean alive;

    /**
     * Accumulated energy above the reproduction threshold.
     * When this reaches REPRODUCTION_ENERGY the agent spawns an offspring.
     */
    private double reproductionAccumulator = 0.0;

    // ── controller (neural network assigned by FREVO) ─────────────────────────
    private Controller controller;

    // ── sensor buffers (set by SimulationServer, read by Controller) ──────────
    private List<Float> sensorSelf     = new ArrayList<>();
    /** Food level on current cell, normalised to [0,1]. Prey only; always 0 for predators. */
    private List<Float> sensorFood     = new ArrayList<>();
    private List<Float> sensor         = new ArrayList<>();

    // ── intended action (set by Controller, consumed by SimulationServer) ─────
    /**
     * Move direction index:
     *   0-5  = step to that hex neighbour
     *   -1   = stay (do nothing)
     *   -2   = stay and eat (PREY only — consume food from current cell)
     */
    public static final int STAY         = -1;
    public static final int STAY_AND_EAT = -2;
    private int intendedMove = STAY;

    // ─────────────────────────────────────────────────────────────────────────
    public Agent(Role role, int id, int q, int r,
                 double initialEnergy, Controller controller) {
        this.role       = role;
        this.id         = id;
        this.q          = q;
        this.r          = r;
        this.energy     = initialEnergy;
        this.alive      = true;
        this.controller = controller;
        controller.setAgent(this);
    }

    // ── sensor setters (called by SimulationServer) ───────────────────────────
    public void setSensorSelf    (List<Float> s) { this.sensorSelf     = s; }
    public void setSensorFood    (List<Float> s) { this.sensorFood     = s; }
    public void setSensor 		 (List<Float> s) { this.sensor         = s; }

    // ── sensor getters (called by EvolvedController) ──────────────────────────
    public List<Float> getSensorSelf    () { return sensorSelf;     }
    public List<Float> getSensorFood    () { return sensorFood;     }
    public List<Float> getSensor	    () { return sensor;     }
    
    // ── action interface ──────────────────────────────────────────────────────
    public int  getIntendedMove()        { return intendedMove; }
    public void setIntendedMove(int dir) { this.intendedMove = dir; }

    // ── energy / alive ────────────────────────────────────────────────────────
    public double getEnergy()         { return energy; }
    public void   addEnergy(double d) { energy = Math.max(0, energy + d); }

    public boolean isAlive() { return alive; }
    public void    die()     { alive = false; }

    /** Call after applying any per-step energy costs; kills the agent if energy has run out. */
    public void checkStarvation() {
        if (energy <= 0) alive = false;
    }

    public double getReproductionAccumulator()    { return reproductionAccumulator; }
    public void   addReproductionEnergy(double d) { reproductionAccumulator += d; }
    public void   resetReproductionAccumulator()  { reproductionAccumulator = 0; }

    // ── position ──────────────────────────────────────────────────────────────
    public int getQ() { return q; }
    public int getR() { return r; }

    public void moveTo(int q, int r) {
        this.q = q;
        this.r = r;
    }

    // ── delegation to controller ──────────────────────────────────────────────
    public Controller getController() { return controller; }

    /** Ask the controller to decide the next action. */
    public void process() { controller.process(); }

    // ── identity ──────────────────────────────────────────────────────────────
    public Role getRole() { return role; }
    public int  getId()   { return id;   }

    @Override
    public String toString() {
        return role + "#" + id + " q=" + q + " r=" + r
                + " energy=" + String.format("%.1f", energy)
                + (alive ? "" : " [DEAD]");
    }
}