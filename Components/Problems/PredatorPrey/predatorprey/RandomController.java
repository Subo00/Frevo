package predatorprey;

import java.util.Random;

/**
 * A dead-simple controller that needs no FREVO neural network.
 * Used by {@link StandaloneRunner} so the simulation can be watched
 * without any evolutionary framework set up.
 *
 * Predators: always pick a random move direction (0-5).
 * Prey:      70% chance to move randomly, 30% chance to eat if there
 *            is food on the current cell (otherwise move).
 */
public class RandomController extends Controller {

    private final Random rng;

    public RandomController(Random rng) {
        this.rng = rng;
    }

    @Override
    public void process() {
        if (agent.getRole() == Agent.Role.PREY) {
            // Try eating if there is food here and the random roll says so
            if (rng.nextFloat() < 0.30f) {
                float foodLevel = agent.getSensorFood().isEmpty()
                        ? 0f : agent.getSensorFood().get(0);
                if (foodLevel > 0f) {
                    agent.setIntendedMove(Agent.STAY_AND_EAT);
                    return;
                }
            }
        }
        // Default: random move direction
        agent.setIntendedMove(rng.nextInt(6));
    }
}
