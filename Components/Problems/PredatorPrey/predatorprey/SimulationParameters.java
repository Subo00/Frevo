package predatorprey;

import java.util.Hashtable;
import java.util.Random;

import core.XMLFieldEntry;

/**
 * Holds all tunable parameters for the predator-prey simulation.
 * Values are loaded from FREVO's XML configuration file via
 * {@link #initialize(Hashtable, Random)} – mirroring {@code PongParameters}.
 *
 * XML keys expected in the problem definition file:
 *   gridRadius          – hex grid radius   (default 8)
 *   numberOfPredators   – predator count    (default 5)
 *   numberOfPrey        – prey count        (default 20)
 *   maximumSteps        – sim length        (default 500)
 *   predatorInitEnergy  – starting energy   (default 100.0)
 *   preyInitEnergy      – starting energy   (default 80.0)
 *   catchRadius         – axial distance at which a predator catches prey (default 1)
 *   energyDecayPredator – energy lost per step by predator (default 0.2)
 *   energyDecayPrey     – energy lost per step by prey     (default 0.1)
 *   energyGainCatch     – energy predator gains on catch   (default 40.0)
 */
public class SimulationParameters {

    private Random random;

    // ── grid ──────────────────────────────────────────────────────────────────
    private int gridRadius = 8;

    // ── population ────────────────────────────────────────────────────────────
    private int numberOfPredators = 5;
    private int numberOfPrey      = 20;

    // ── timing ────────────────────────────────────────────────────────────────
    private int maximumSteps = 500;

    // ── energy ────────────────────────────────────────────────────────────────
    private double agentInitEnergy  = 100.0;
    private double energyDecayPredator =   2.0;
    private double energyDecayPrey     =   1.0;
    private double energyGainCatch     =  40.0;

    // ── food ──────────────────────────────────────────────────────────────────
    /** Food units a prey consumes per eat-action (must be <= HexCell.FOOD_EAT_AMOUNT max). */
    private int    foodEatAmount    = 30;
    /** Energy gained by prey per food unit eaten. */
    private double foodEnergyGain   = 15.0;
    /** Minimum food each cell starts with at simulation reset. */
    private int    foodInitMin      = 0;
    /** Maximum food each cell starts with at simulation reset. */
    private int    foodInitMax      = 50;

    // ── misc ──────────────────────────────────────────────────────────────────
    private boolean debugging = false;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by {@link PredatorPrey#evaluateFitness} with FREVO's property map.
     * Reads values from the XML configuration; uses field defaults if a key
     * is absent (making all parameters optional for quick prototyping).
     */
    public void initialize(Hashtable<String, XMLFieldEntry> props, Random random) {
        this.random = random;

        gridRadius        = intOr(props, "gridRadius",        gridRadius);
        numberOfPredators = intOr(props, "numberOfPredators", numberOfPredators);
        numberOfPrey      = intOr(props, "numberOfPrey",      numberOfPrey);
        maximumSteps      = intOr(props, "maximumSteps",      maximumSteps);

        agentInitEnergy  = dblOr(props, "agentInitEnergy",  agentInitEnergy);
        energyDecayPredator = dblOr(props, "energyDecayPredator", energyDecayPredator);
        energyDecayPrey     = dblOr(props, "energyDecayPrey",     energyDecayPrey);
        energyGainCatch     = dblOr(props, "energyGainCatch",     energyGainCatch);

        // food
        foodEatAmount  = intOr(props, "foodEatAmount",  foodEatAmount);
        foodEnergyGain = dblOr(props, "foodEnergyGain", foodEnergyGain);
        foodInitMin    = intOr(props, "foodInitMin",    foodInitMin);
        foodInitMax    = intOr(props, "foodInitMax",    foodInitMax);
        Agent.MAX_ENERGY = agentInitEnergy;   // FIX: energy cap
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static int intOr(Hashtable<String, XMLFieldEntry> p, String k, int def) {
        XMLFieldEntry e = p.get(k);
        return e != null ? Integer.parseInt(e.getValue()) : def;
    }

    private static double dblOr(Hashtable<String, XMLFieldEntry> p, String k, double def) {
        XMLFieldEntry e = p.get(k);
        return e != null ? Double.parseDouble(e.getValue()) : def;
    }

    // ── getters ───────────────────────────────────────────────────────────────

    public Random getRandom()             { return random; }
    public int    getGridRadius()         { return gridRadius; }
    public int    getNumberOfPredators()  { return numberOfPredators; }
    public int    getNumberOfPrey()       { return numberOfPrey; }
    public int    getMaximumSteps()       { return maximumSteps; }
    public double getAgentInitEnergy()    { return agentInitEnergy; }
    public double getEnergyDecayPredator(){ return energyDecayPredator; }
    public double getEnergyDecayPrey()    { return energyDecayPrey; }
    public double getEnergyGainCatch()    { return energyGainCatch; }
    public boolean isDebugging()          { return debugging; }
    public void setDebugging(boolean d)   { this.debugging = d; }

    public int    getFoodEatAmount()      { return foodEatAmount;  }
    public double getFoodEnergyGain()     { return foodEnergyGain; }
    public int    getFoodInitMin()        { return foodInitMin;    }
    public int    getFoodInitMax()        { return foodInitMax;    }
}