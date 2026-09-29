package predatorprey;

import java.util.ArrayList;

import core.AbstractRepresentation;

/**
 * Connects a FREVO {@link AbstractRepresentation} (e.g. a neural network)
 * to an {@link Agent} – mirrors {@code EvolvedController} in Pong.
 *
 * Input vector layout (all values in [0, 1]):
 *   [0]   own energy (normalised by role's initial energy)
 *   [1]   own Q position (normalised)
 *   [2]   own R position (normalised)
 *   [3]   nearest-opponent delta-Q (normalised)
 *   [4]   nearest-opponent delta-R (normalised)
 *   [5]   nearest-opponent distance (normalised)
 *   [6]   nearest-ally delta-Q (normalised)
 *   [7]   nearest-ally delta-R (normalised)
 *   [8]   food on current cell (normalised to [0,1]) — prey only, always 0 for predators
 *
 * Output vector layout:
 *   [0..5] – activation for each of the 6 hex movement directions
 *   [6]    – eat action (prey only: consume food from current cell instead of moving)
 *
 * Decision rule:
 *   The highest-activation output wins, subject to a 0.5 threshold.
 *   If no output clears 0.5 the agent stays put (Agent.STAY).
 *   For predators, output [6] is ignored even if it is the maximum.
 */
public class EvolvedController extends Controller {
    // DIAG instrumentation
    public static long[][] actHist = new long[2][8]; // [role][dir0..5, stay, eat]
    public static long[] ties = new long[2], decisions = new long[2];
    public static double[] inMin = new double[10], inMax = new double[10], inSum = new double[10]; public static long inN=0;
    static { java.util.Arrays.fill(inMin, 1e30); java.util.Arrays.fill(inMax, -1e30); }


    /** The neural network / representation assigned by FREVO. */
    private final AbstractRepresentation representation;

    public EvolvedController(AbstractRepresentation representation) {
        this.representation = representation;
    }

    @Override
    public void process() {
        ArrayList<Float> inputs  = new ArrayList<>();
        ArrayList<Float> outputs;

        // Reset recurrent state for stateful representations (e.g. RNNs)
        representation.reset();

        // ── assemble input vector ────────────────────────────────────────────
        inputs.addAll(agent.getSensorSelf());     // [0-2]  own position + energy
       // inputs.addAll(agent.getSensorNearest());  // [3-5]  nearest opponent
       // inputs.addAll(agent.getSensorNeighbor()); // [6-7]  nearest ally
        inputs.addAll(agent.getSensorFood());     // [3]    food on current cell
        inputs.addAll(agent.getSensor());		  // [4-9]  sensors of six directions
        
        // ── query the network ────────────────────────────────────────────────
        outputs = representation.getOutput(inputs);

        // ── decode output: pick highest-activation output above threshold ─────
        // Outputs [0..5] = move directions, [6] = eat action (prey only)
        int   maxOutputs = agent.getRole() == Agent.Role.PREY ? 7 : 6;
        int   bestAction = Agent.STAY;    // default: do nothing
        float bestVal    = 0.1f;          // lowered threshold so untrained networks still move

        for (int i = 0; i < Math.min(maxOutputs, outputs.size()); i++) {
            if (outputs.get(i) > bestVal) {
                bestVal    = outputs.get(i);
                // Output index 6 maps to the eat action; 0-5 map to move directions
                bestAction = (i == 6) ? Agent.STAY_AND_EAT : i;
            }
        }

        int ri = agent.getRole()==Agent.Role.PREY?1:0;
        int nAtBest=0; for (int i = 0; i < Math.min(maxOutputs, outputs.size()); i++) if (outputs.get(i)==bestVal) nAtBest++;
        decisions[ri]++; if (nAtBest>1 && bestAction!=Agent.STAY) ties[ri]++;
        actHist[ri][bestAction>=0?bestAction:(bestAction==Agent.STAY?6:7)]++;
        for (int i=0;i<inputs.size()&&i<10;i++){double v=inputs.get(i); inMin[i]=Math.min(inMin[i],v); inMax[i]=Math.max(inMax[i],v); inSum[i]+=v;} inN++;
        agent.setIntendedMove(bestAction);
    }
}
