package predatorprey;

/**
 * Abstract controller for an agent – mirrors {@code Controller} in Pong.
 *
 * Concrete subclasses receive sensor data through the owning {@link Agent}
 * and must implement {@link #process()} to set the agent's intended action.
 */
public abstract class Controller {

    /** The agent this controller is driving. */
    protected Agent agent;

    /** Optional back-references for debug/advanced controllers. */
    protected SimulationParameters parameters;
    protected SimulationState       state;

    // ── wiring ────────────────────────────────────────────────────────────────

    /** Called once at construction time by the Agent. */
    public final void setAgent(Agent agent) {
        this.agent = agent;
    }

    public void setParameters(SimulationParameters p) { this.parameters = p; }
    public void setState     (SimulationState       s) { this.state      = s; }

    public boolean isDebugging() {
        return parameters != null && parameters.isDebugging();
    }

    // ── to implement ──────────────────────────────────────────────────────────

    /**
     * Reads sensor data from {@code agent}, decides on an action,
     * and writes it back via {@code agent.setIntendedMove(dir)}.
     */
    public abstract void process();
}
