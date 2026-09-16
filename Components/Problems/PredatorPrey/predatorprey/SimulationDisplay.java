package predatorprey;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.*;
import javax.swing.border.TitledBorder;

import core.AbstractMultiProblem.RepresentationWithScore;
import main.FrevoMain;

/**
 * Graphical replay window for the predator-prey simulation.
 * Mirrors {@code PongDisplay} structurally:
 *   – JFrame with a control panel (start / stop buttons)
 *   – inner {@link DrawPanel} that paints the hex grid
 *   – inner {@link DisplayWorker} SwingWorker that drives {@link SimulationServer}
 *     on a background thread (same pattern as PongDisplay.DisplayWorker)
 *
 * Colour scheme:
 *   background  #0F172A  (near-black navy)
 *   empty cell  #1E293B
 *   predator    #F87171  (red)
 *   prey        #4ADE80  (green)
 *   border      #334155
 */
public class SimulationDisplay extends JFrame implements WindowListener {

    private static final long serialVersionUID = 1L;

    // ── colours ───────────────────────────────────────────────────────────────
    private static final Color BG_COLOR       = new Color(0x0F172A);
    private static final Color CELL_EMPTY     = new Color(0x1E293B);
    private static final Color CELL_PREDATOR  = new Color(0xF87171);
    private static final Color CELL_PREY      = new Color(0x4ADE80);
    private static final Color CELL_BORDER    = new Color(0x334155);
    private static final Color TEXT_COLOR     = Color.WHITE;

    // ── agent icons ───────────────────────────────────────────────────────────
    // Place your image files inside src/predatorprey/ in the Eclipse project.
    // Filenames must match these constants exactly.
    // Any square PNG works (64x64 or 128x128 recommended).
    // If a file is not found the simulation falls back to coloured hexagons.
    private static final String PREDATOR_IMAGE = "predator.png";
    private static final String PREY_IMAGE     = "prey.png";

    private Image predatorImg = null;
    private Image preyImg     = null;

    // ── UI components ─────────────────────────────────────────────────────────
    private JPanel      menuPanel;
    private DrawPanel   canvasPanel;
    protected JButton   startButton;
    protected JButton   stopButton;
    protected JCheckBox debugCheckbox;

    // ── simulation state ──────────────────────────────────────────────────────
    private DisplayWorker        workerThread;
    private SimulationParameters parameters;
    private SimulationState      state;
    private SimulationServer     server = null;   // null until simulation starts

    // ─────────────────────────────────────────────────────────────────────────

    SimulationDisplay(SimulationParameters parameters, SimulationState state) {
        super("Predator-Prey Simulation");

        this.parameters = parameters;
        this.state      = state;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setBounds(0, 0, 720, 760);
        Container con = getContentPane();
        con.setLayout(new BoxLayout(con, BoxLayout.Y_AXIS));

        // ── menu bar ─────────────────────────────────────────────────────────
        menuPanel = new JPanel();
        menuPanel.setPreferredSize(new Dimension(400, 50));
        menuPanel.setMinimumSize  (new Dimension(400, 50));
        menuPanel.setMaximumSize  (new Dimension(Integer.MAX_VALUE, 50));
        menuPanel.setBorder(new TitledBorder("Control"));
        menuPanel.setLayout(new BoxLayout(menuPanel, BoxLayout.X_AXIS));

        startButton = new JButton();
        stopButton  = new JButton();
        stopButton.setEnabled(false);
        debugCheckbox = new JCheckBox("Debug");

        // Load play/stop icons from the FREVO icon JAR (same as PongDisplay)
        try {
            Icon playIcon = new ImageIcon(new java.net.URL(
                    "jar:file:" + FrevoMain.getInstallDirectory()
                    + "/Libraries/jlfgr/jlfgr-1_0.jar!/toolbarButtonGraphics/media/Play24.gif"));
            Icon stopIcon = new ImageIcon(new java.net.URL(
                    "jar:file:" + FrevoMain.getInstallDirectory()
                    + "/Libraries/jlfgr/jlfgr-1_0.jar!/toolbarButtonGraphics/media/Stop24.gif"));
            startButton.setIcon(playIcon);
            stopButton .setIcon(stopIcon);
        } catch (Exception e) {
            // Icon JAR not found – fall back to text labels
            startButton.setText("Play");
            stopButton .setText("Stop");
        }

        startButton.addActionListener(e -> {
            startButton  .setEnabled(false);
            stopButton   .setEnabled(true);
            debugCheckbox.setEnabled(false);
            parameters.setDebugging(debugCheckbox.isSelected());
            workerThread = new DisplayWorker();
            workerThread.execute();
        });

        stopButton.addActionListener(e -> {
            startButton  .setEnabled(true);
            stopButton   .setEnabled(false);
            debugCheckbox.setEnabled(true);
            if (workerThread != null) workerThread.stopSimulation();
        });

        menuPanel.add(startButton);
        menuPanel.add(stopButton);
        menuPanel.add(debugCheckbox);
        con.add(menuPanel);

        // ── canvas ────────────────────────────────────────────────────────────
        canvasPanel = new DrawPanel(parameters, state);
        canvasPanel.setBackground(BG_COLOR);
        con.add(canvasPanel);

        canvasPanel.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { e.getComponent().repaint(); }
        });

        addWindowListener(this);
        setLocationRelativeTo(null);
        setVisible(true);

        // Load agent icons — looks in src/predatorprey/ inside the project
        predatorImg = loadImage(PREDATOR_IMAGE);
        preyImg     = loadImage(PREY_IMAGE);

        canvasPanel.repaint();
    }

    // ── inner drawing panel ───────────────────────────────────────────────────

    class DrawPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        private SimulationParameters parameters;
        private SimulationState      state;

        DrawPanel(SimulationParameters parameters, SimulationState state) {
            this.parameters = parameters;
            this.state      = state;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (server == null) return;    // nothing to draw until server is started

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // ── compute hex size so the grid fits the panel ───────────────────
            int margin  = 30;
            int usableW = getWidth()  - 2 * margin;
            int usableH = getHeight() - 2 * margin;

            // For a hex-grid of radius R, the bounding box (flat-top) is:
            //   width  ≈ (2R + 1) * 1.5 * hexSize  → hexSize = usableW / ((2R+1)*1.5)
            //   height ≈ (2R + 1) * sqrt(3) * hexSize
            int    R       = parameters.getGridRadius();
            double sizeByW = usableW / ((2.0 * R + 1) * 1.5);
            double sizeByH = usableH / ((2.0 * R + 1) * Math.sqrt(3));
            int    hexSize = (int) Math.min(sizeByW, sizeByH);
            hexSize = Math.max(hexSize, 4);   // never smaller than 4 px

            int cx = getWidth()  / 2;
            int cy = getHeight() / 2;

            // ── draw each hex cell ────────────────────────────────────────────
            HexGrid grid = state.getGrid();
            if (grid == null) return;

            for (HexCell cell : grid.getAllCells()) {
                int px = (int) Math.round(cx + hexSize * 1.5  * cell.getQ());
                int py = (int) Math.round(cy + hexSize * Math.sqrt(3)
                                               * (cell.getR() + cell.getQ() * 0.5));

                Polygon hex = flatTopHex(px, py, hexSize);

                g2.setColor(cellFill(cell));
                g2.fillPolygon(hex);
                g2.setColor(CELL_BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.drawPolygon(hex);
            }

            // ── draw agent icons and energy bars ─────────────────────────────
            if (hexSize >= 10) {
                drawAgentImages(g2, state.getPredators(), cx, cy, hexSize, predatorImg);
                drawAgentImages(g2, state.getPrey(),      cx, cy, hexSize, preyImg);
                drawEnergyBars(g2, state.getPredators(), cx, cy, hexSize,
                        parameters.getAgentInitEnergy(), new Color(0xFF6B6B));
                drawEnergyBars(g2, state.getPrey(),      cx, cy, hexSize,
                        parameters.getAgentInitEnergy(),     new Color(0x6BFF8E));
            }

            // ── HUD: step counter, alive counts, fitness ──────────────────────
            g2.setColor(TEXT_COLOR);
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            long alivePred = state.getPredators().stream().filter(Agent::isAlive).count();
            long alivePrey = state.getPrey()     .stream().filter(Agent::isAlive).count();
            g2.drawString(String.format("Step: %d / %d",
                    state.getActualStep(), parameters.getMaximumSteps()), margin, margin - 10);
            g2.drawString(String.format("Predators alive: %d   Prey alive: %d   Catches: %d",
                    alivePred, alivePrey, state.getCatchCount()), margin + 160, margin - 10);
        }

        private Polygon flatTopHex(int cx, int cy, int size) {
            Polygon p = new Polygon();
            for (int i = 0; i < 6; i++) {
                double angle = Math.toRadians(60 * i);
                p.addPoint(
                    (int) Math.round(cx + size * Math.cos(angle)),
                    (int) Math.round(cy + size * Math.sin(angle))
                );
            }
            return p;
        }

        private Color cellFill(HexCell cell) {
            switch (cell.getState()) {
                case PREDATOR:
                    // neutral background when icon is loaded; fallback tint otherwise
                    return predatorImg != null ? CELL_EMPTY : CELL_PREDATOR;
                case PREY:
                    return preyImg != null ? CELL_EMPTY : CELL_PREY;
                default:
                    // tint empty cells by food level so grass is visible
                    float f = (float) cell.getFood() / HexCell.FOOD_MAX;
                    if (f < 0.01f) return CELL_EMPTY;
                    return blend(CELL_EMPTY, new Color(0x1A3A1A), f * 0.85f);
            }
        }

        private Color blend(Color a, Color b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            return new Color(
                    (int)(a.getRed()   + (b.getRed()   - a.getRed())   * t),
                    (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    (int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
        }

        private Color dim(Color c, float t) {
            t = Math.max(0f, Math.min(1f, t));
            return new Color((int)(c.getRed()*t), (int)(c.getGreen()*t), (int)(c.getBlue()*t));
        }

        private void drawAgentImages(Graphics2D g2, java.util.List<Agent> agents,
                                     int cx, int cy, int hexSize, Image img) {
            if (img == null) return;
            int iconSize = (int)(hexSize * 1.4);
            for (Agent a : agents) {
                if (!a.isAlive()) continue;
                int px = (int) Math.round(cx + hexSize * 1.5  * a.getQ());
                int py = (int) Math.round(cy + hexSize * Math.sqrt(3)
                                               * (a.getR() + a.getQ() * 0.5));
                g2.drawImage(img, px - iconSize / 2, py - iconSize / 2,
                             iconSize, iconSize, null);
            }
        }

        private void drawEnergyBars(Graphics2D g2, java.util.List<Agent> agents,
                                    int cx, int cy, int hexSize,
                                    double maxE, Color fullColor) {
            for (Agent a : agents) {
                if (!a.isAlive()) continue;
                int px = (int) Math.round(cx + hexSize * 1.5  * a.getQ());
                int py = (int) Math.round(cy + hexSize * Math.sqrt(3)
                                               * (a.getR() + a.getQ() * 0.5));
                int   bw    = hexSize;
                int   bh    = Math.max(3, hexSize / 8);
                int   bx    = px - bw / 2;
                int   by    = py - hexSize + 2;
                float ratio = (float) Math.min(a.getEnergy() / maxE, 1.0);
                g2.setColor(new Color(0x1E293B));
                g2.fillRect(bx, by, bw, bh);
                g2.setColor(dim(fullColor, ratio));
                g2.fillRect(bx, by, (int)(bw * ratio), bh);
            }
        }

        private Agent agentAt(java.util.List<Agent> agents, int q, int r) {
            for (Agent a : agents)
                if (a.isAlive() && a.getQ() == q && a.getR() == r) return a;
            return null;
        }
    }

    // ── background worker (mirrors PongDisplay.DisplayWorker) ─────────────────

    private class DisplayWorker extends SwingWorker<Void, Integer> {

        @Override
        protected Void doInBackground() throws Exception {
            server = new SimulationServer(parameters, state);
            server.runSimulation(canvasPanel);   // drives repaint each step

            List<RepresentationWithScore> scores = server.getResults();
            System.out.printf("Predator fitness: %.0f   Prey fitness: %.0f%n",
                    scores.get(0).getScore(), scores.get(1).getScore());
            return null;
        }

        public void stopSimulation() {
            if (server != null) server.stop();
        }

        @Override
        protected void done() {
            // Re-enable buttons and surface any exceptions
            startButton.setEnabled(true);
            stopButton .setEnabled(false);
            try {
                get();
            } catch (InterruptedException | ExecutionException ex) {
                throw new RuntimeException(ex.getCause() != null ? ex.getCause() : ex);
            }
        }
    }

    // ── image loading ────────────────────────────────────────────────────────

    /**
     * Loads an image from the same package folder as this class (src/predatorprey/).
     * Returns null silently if the file is not found — falls back to coloured hexagons.
     *
     * To add icons:
     *   1. Copy your PNG files into src/predatorprey/ in the Eclipse project
     *   2. Make sure filenames match PREDATOR_IMAGE and PREY_IMAGE above
     *   3. Run — images appear automatically
     */
    private Image loadImage(String filename) {
        try {
            java.net.URL url = getClass().getResource(filename);
            if (url == null) {
                System.out.println("Icon not found (using colour fallback): " + filename);
                return null;
            }
            Image img = javax.imageio.ImageIO.read(url);
            System.out.println("Icon loaded: " + filename);
            return img;
        } catch (Exception e) {
            System.out.println("Could not load icon: " + filename + " — " + e.getMessage());
            return null;
        }
    }

    // ── WindowListener (boilerplate, same as PongDisplay) ─────────────────────

    @Override public void windowOpened     (WindowEvent e) {}
    @Override public void windowClosing    (WindowEvent e) { if (workerThread != null) workerThread.stopSimulation(); }
    @Override public void windowClosed     (WindowEvent e) {}
    @Override public void windowIconified  (WindowEvent e) {}
    @Override public void windowDeiconified(WindowEvent e) {}
    @Override public void windowActivated  (WindowEvent e) {}
    @Override public void windowDeactivated(WindowEvent e) {}
}
