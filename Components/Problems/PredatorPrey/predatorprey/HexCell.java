package predatorprey;



/**
 * Represents a single hexagonal cell using axial coordinates (q, r).
 *
 * Axial coordinates are the standard system for hex grids:
 *   q = column axis, r = row axis, s = -q-r (implied, never stored)
 *
 * CellState is read by {@link SimulationDisplay} for rendering and
 * written by {@link SimulationServer} as agents move.
 *
 * ── Food / grass mechanic ────────────────────────────────────────────────────
 * Each cell holds an integer food value in [0, FOOD_MAX].
 * Every simulation tick, food grows according to:
 *
 *   gain = (currentFood * FOOD_SELF_GROWTH_RATE)
 *        + sum over neighbours where neighbour.food >= FOOD_SPREAD_THRESHOLD:
 *              (neighbour.food / FOOD_SPREAD_THRESHOLD)
 *
 * Growth is computed in double-buffer fashion by {@link HexGrid#tickFoodGrowth()}
 * so that every cell sees the same food values from the previous tick,
 * regardless of evaluation order.
 *
 * Prey agents can spend a turn eating instead of moving, consuming
 * FOOD_EAT_AMOUNT units from their current cell in exchange for energy.
 */
public class HexCell {

    // ── food constants ────────────────────────────────────────────────────────
    /** Maximum food a cell can hold. */
    public static final int    FOOD_MAX               = 100;
    /** Neighbours must have at least this much food to spread to this cell. */
    public static final int    FOOD_SPREAD_THRESHOLD  = 80;   // slowed: was 30
  /** Food consumed by one herbivore eat-action. */

    public static final double FOOD_GROWTH_RATE = 0.08;
    // ── axial coordinates ─────────────────────────────────────────────────────
    private final int q;
    private final int r;

    // ── occupancy state ───────────────────────────────────────────────────────
    private CellState state;

    public enum CellState {
        EMPTY, PREY, PREDATOR
    }

    // ── food ──────────────────────────────────────────────────────────────────
    /** Current food level, integer in [0, FOOD_MAX]. */
    private int food;

    int nextFood;   // package-private: written only by HexGrid

    /**
     * The 6 axial direction vectors for flat-top hexagons.
     * Each pair {dq, dr} points to one of the 6 neighbours.
     * Direction index matches {@code Agent.intendedMove}.
     */
    public static final int[][] DIRECTIONS = {
        { 1,  0},   // 0 – right
        { 1, -1},   // 1 – upper-right
        { 0, -1},   // 2 – upper-left
        {-1,  0},   // 3 – left
        {-1,  1},   // 4 – lower-left
        { 0,  1}    // 5 – lower-right
    };

    // ─────────────────────────────────────────────────────────────────────────

    public HexCell(int q, int r, int initialFood) {
        this.q     = q;
        this.r     = r;
        this.food  = Math.min(Math.max(initialFood, 0), FOOD_MAX);
        this.state = CellState.EMPTY;
    }

    // ── food interface ────────────────────────────────────────────────────────

    public int  getFood()            { return food; }
    public void setFood(int f)       { food = Math.min(Math.max(f, 0), FOOD_MAX); }

    /**
     * Removes up to {@code amount} food from this cell and returns how much
     * was actually removed (may be less if the cell had less food than requested).
     */
    public int consumeFood(int amount) {
        int consumed = Math.min(food, amount);
        food -= consumed;
        return consumed;
    }


    void computeNextFood(HexCell[] neighbours) {
    	 double logisticGain = FOOD_GROWTH_RATE * food * (1.0 - (double) food / FOOD_MAX);

         // Seed dispersal: rich neighbours spread grass to adjacent cells
         double spread = 0;
         for (HexCell n : neighbours) {
             if (n.food >= FOOD_SPREAD_THRESHOLD) {
                 spread += (double) n.food / FOOD_SPREAD_THRESHOLD;
             }
         }

         int raw = food + (int)(logisticGain + spread);
         nextFood = Math.min(Math.max(raw, 0), FOOD_MAX);

    }

    void applyNextFood() { food = nextFood; }
    // ── occupancy accessors ───────────────────────────────────────────────────

    public int       getQ()     { return q; }
    public int       getR()     { return r; }
    public CellState getState() { return state; }
    public void      setState(CellState state) { this.state = state; }

    /**
     * Returns the axial coordinates of all 6 neighbours.
     * Neighbours may lie outside the grid bounds – callers must check
     * {@link HexGrid#inBounds(int, int)} before use.
     */
    public int[][] neighborCoords() {
        int[][] neighbors = new int[6][2];
        for (int i = 0; i < 6; i++) {
            neighbors[i][0] = q + DIRECTIONS[i][0];
            neighbors[i][1] = r + DIRECTIONS[i][1];
        }
        return neighbors;
    }

    @Override
    public String toString() {
        return "HexCell(" + q + ", " + r + ") [" + state + ", food=" + food + "]";
    }
}
