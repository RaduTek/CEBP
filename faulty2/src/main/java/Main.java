import com.gameoflife.Cell;
import com.gameoflife.CellState;
import com.gameoflife.CellType;
import com.gameoflife.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.Map;

/** Starts the visual, live map. Pass a duration in milliseconds for an auto-stop. */
public class Main {
    private static final long T_FULL_MS = 1_000;
    private static final long T_STARVE_MS = 2_000;
    private static final long MATING_TIMEOUT_MS = 3_000;
    private static final int FOOD_CAPACITY = 200, INITIAL_FOOD = 50;
    private static final int INITIAL_SEXUAL_CELLS = 20, INITIAL_ASEXUAL_CELLS = 30;

    public static void main(String[] args) {
        long autoStopMs = args.length == 0 ? 0 : Long.parseLong(args[0]);
        SwingUtilities.invokeLater(() -> showSimulation(autoStopMs));
    }

    private static void showSimulation(long autoStopMs) {
        SimulationEngine.Config config = new SimulationEngine.Config(
                T_FULL_MS, T_STARVE_MS, MATING_TIMEOUT_MS, 500,
                2_000, 3, FOOD_CAPACITY, INITIAL_FOOD,
                INITIAL_SEXUAL_CELLS, INITIAL_ASEXUAL_CELLS, "statistics.csv");
        SimulationEngine engine = new SimulationEngine(config);
        WorldView world = new WorldView(engine);
        JFrame frame = new JFrame("The Game of Life — Concurrent Map");
        JLabel stats = new JLabel("Starting simulation…");
        JButton stop = new JButton("Stop simulation");
        JPanel footer = new JPanel(new BorderLayout(12, 6));
        footer.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JPanel statusRow = new JPanel(new BorderLayout(12, 0));
        statusRow.add(stats, BorderLayout.CENTER);
        statusRow.add(stop, BorderLayout.EAST);
        footer.add(statusRow, BorderLayout.NORTH);
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 2));
        legend.add(legendItem(new Color(105, 214, 129), "Food"));
        legend.add(legendItem(new Color(105, 170, 255), "Sexual cell"));
        legend.add(legendItem(new Color(255, 172, 79), "Asexual cell"));
        legend.add(legendItem(new Color(229, 119, 239), "Reproducing cell"));
        footer.add(legend, BorderLayout.SOUTH);
        frame.setLayout(new BorderLayout());
        frame.add(world, BorderLayout.CENTER);
        frame.add(footer, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Timer[] refreshTimer = new Timer[1];
        Runnable shutdown = () -> {
            stop.setEnabled(false);
            if (refreshTimer[0] != null) refreshTimer[0].stop();
            try { engine.shutdown(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            frame.dispose();
        };
        stop.addActionListener(e -> shutdown.run());
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown.run(); }
        });
        engine.startSimulation();
        refreshTimer[0] = new Timer(100, e -> {
            world.repaint();
            stats.setText("Cells: " + engine.getPopulation() + "    Food: " + engine.getResources().getAvailableFood()
                    + "    Births: " + engine.getBirths() + "    Deaths: " + engine.getDeaths()
                    + "    Meals: " + engine.getMeals());
        });
        refreshTimer[0].start();
        if (autoStopMs > 0) new Timer((int) Math.min(Integer.MAX_VALUE, autoStopMs), e -> {
            ((Timer) e.getSource()).stop(); shutdown.run();
        }).start();
        frame.pack();
        frame.setMinimumSize(new Dimension(850, 560));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JLabel legendItem(Color color, String text) {
        JLabel item = new JLabel(text, new DotIcon(color), SwingConstants.LEFT);
        item.setIconTextGap(6);
        return item;
    }

    private static final class DotIcon implements Icon {
        private final Color color;
        DotIcon(Color color) { this.color = color; }
        @Override public int getIconWidth() { return 12; }
        @Override public int getIconHeight() { return 12; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.setColor(color);
            graphics.fillOval(x + 1, y + 1, 10, 10);
            graphics.setColor(new Color(9, 20, 31));
            graphics.drawOval(x + 1, y + 1, 10, 10);
        }
    }

    /** Paints best-effort copies while simulation state continues changing on worker threads. */
    private static final class WorldView extends JPanel {
        private final SimulationEngine engine;
        WorldView(SimulationEngine engine) { this.engine = engine; setPreferredSize(new Dimension(1000, 620)); setBackground(new Color(18, 27, 37)); }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = engine.getWidth(), height = engine.getHeight();
            double tileW = (double) getWidth() / width, tileH = (double) getHeight() / height;
            int[] food = engine.getFoodSnapshot();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int px = (int) (x * tileW), py = (int) (y * tileH);
                g.setColor(new Color(31, 43, 54));
                g.drawRect(px, py, (int) Math.ceil(tileW), (int) Math.ceil(tileH));
                int amount = food[y * width + x];
                if (amount > 0) {
                    int diameter = Math.min(8 + amount * 2, (int) Math.min(tileW, tileH) - 4);
                    g.setColor(new Color(105, 214, 129));
                    g.fillOval(px + ((int) tileW - diameter) / 2, py + ((int) tileH - diameter) / 2, diameter, diameter);
                    if (amount > 1) {
                        g.setColor(new Color(14, 36, 24));
                        g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
                        g.drawString(Integer.toString(amount), px + 2, py + 11);
                    }
                }
            }
            Map<Integer, Integer> stacked = new HashMap<>();
            for (Cell cell : engine.getCellsSnapshot()) {
                int x = cell.getX(), y = cell.getY();
                int offset = stacked.merge(y * width + x, 1, Integer::sum) - 1;
                int diameter = Math.max(8, (int) Math.min(tileW, tileH) - 5);
                int px = (int) (x * tileW + (tileW - diameter) / 2) + offset * 2;
                int py = (int) (y * tileH + (tileH - diameter) / 2) + offset * 2;
                Color color = cell.getType() == CellType.SEXUAL ? new Color(105, 170, 255) : new Color(255, 172, 79);
                if (cell.getState() == CellState.HUNGRY) color = color.darker();
                if (cell.getState() == CellState.REPRODUCING) color = new Color(229, 119, 239);
                g.setColor(color);
                g.fillOval(px, py, diameter, diameter);
                g.setColor(new Color(9, 20, 31));
                g.drawOval(px, py, diameter, diameter);
            }
            g.dispose();
        }
    }
}
