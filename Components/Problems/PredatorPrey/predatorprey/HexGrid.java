package predatorprey;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * A hexagonal grid stored as a map from axial coordinates to {@link HexCell}.
 *
 * Uses a {@code HashMap} with a packed {@code long} key so all lookups are O(1).
 * The grid shape is a filled hexagon of the given radius:
 *   radius=0 → 1 cell, radius=1 → 7 cells, radius=n → 3n²+3n+1 cells.
 *
 * This class is unchanged in logic from the prototype; the package declaration
 * is the only edit required to integrate with {@link SimulationState} and
 * {@link SimulationServer}.
 */
public class HexGrid {

    private final Map<Long, HexCell> cells = new HashMap<>();
    private final int radius;

    /**
     * Builds a filled hexagonal grid with the given radius.
     * Every cell starts with {@code initialFood} food units.
     */
    public HexGrid(int radius, int initialFood) {
        this.radius = radius;
        for (int q = -radius; q <= radius; q++) {
            int r1 = Math.max(-radius, -q - radius);
            int r2 = Math.min( radius, -q + radius);
            for (int r = r1; r <= r2; r++) {
                cells.put(key(q, r), new HexCell(q, r, initialFood));
            }
        }
    }

    /**
     * Advances food growth by one tick for every cell in the grid.
     *
     * Uses double-buffering so all cells see the food values from the
     * previous tick when computing their gain — a cell that grew a lot
     * this tick does not immediately benefit its neighbours in the same tick.
     */
    public void tickFoodGrowth() {
        // Step 1 – compute next values into each cell's staging buffer
        for (HexCell cell : cells.values()) {
            cell.computeNextFood(getNeighbors(cell));
        }
        // Step 2 – commit all at once
        for (HexCell cell : cells.values()) {
            cell.applyNextFood();
        }
    }

    // ── lookup ────────────────────────────────────────────────────────────────

    /** Returns the cell at (q, r), or {@code null} if out of bounds. */
    public HexCell getCell(int q, int r) {
        return cells.get(key(q, r));
    }

    /** Returns {@code true} if (q, r) is a valid cell in this grid. */
    public boolean inBounds(int q, int r) {
        return cells.containsKey(key(q, r));
    }

    /** All cells in the grid (unordered). */
    public Collection<HexCell> getAllCells() {
        return cells.values();
    }

    /**
     * Returns the (up to 6) existing neighbours of a cell.
     * Out-of-bounds neighbour coordinates are silently skipped.
     */
    public HexCell[] getNeighbors(HexCell cell) {
        int[][] coords  = cell.neighborCoords();
        HexCell[] buf   = new HexCell[6];
        int count = 0;
        for (int[] c : coords) {
            HexCell n = getCell(c[0], c[1]);
            if (n != null) buf[count++] = n;
        }
        HexCell[] result = new HexCell[count];
        System.arraycopy(buf, 0, result, 0, count);
        return result;
    }

    // ── meta ──────────────────────────────────────────────────────────────────

    public int getRadius() { return radius; }
    public int size()      { return cells.size(); }

    // ── key packing ───────────────────────────────────────────────────────────

    /** Packs two signed ints into one long for O(1) map lookup. */
    private static long key(int q, int r) {
        return ((long) q << 32) | (r & 0xFFFFFFFFL);
    }
}
