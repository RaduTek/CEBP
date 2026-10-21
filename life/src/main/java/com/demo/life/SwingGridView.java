package com.demo.life;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public final class SwingGridView {
    // Tile size in pixels; auto-shrinks for very large grids
    private static final int MAX_CELL = 20;
    private static final int MIN_CELL = 6;
    private static final int STATS_H  = 52;

    // Dark-theme palette
    private static final Color BG       = new Color(0x0d, 0x11, 0x17);
    private static final Color EMPTY    = new Color(0x1e, 0x1e, 0x2e);
    private static final Color FOOD     = new Color(0x00, 0xd2, 0x80);
    private static final Color ASEXUAL  = new Color(0x4a, 0x9e, 0xff);
    private static final Color SEXUAL   = new Color(0xff, 0xa0, 0x30);
    private static final Color MATING   = new Color(0xff, 0x5c, 0xa8);
    private static final Color STARVING = new Color(0xff, 0x3c, 0x3c);

    private final World world;
    private final int cellPx;

    private JFrame   frame;
    private GridPanel gridPanel;
    private JLabel   statsLabel;

    public SwingGridView(World world) {
        this.world = world;
        // Pick a cell pixel size that keeps the window under ~1200px wide
        int w = world.config.width();
        int ideal = Math.min(MAX_CELL, 1200 / Math.max(w, 1));
        this.cellPx = Math.max(MIN_CELL, ideal);
    }

    /** Call once from any thread; builds and shows the window on the EDT. */
    public void start() {
        SwingUtilities.invokeLater(this::buildUI);
    }

    private void buildUI() {
        frame = new JFrame("Game of Life — Concurrency Demo");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { world.running = false; }
        });
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout());

        gridPanel  = new GridPanel();
        statsLabel = buildStatsLabel();

        JPanel bottom = new JPanel(new BorderLayout(0, 0));
        bottom.setBackground(new Color(0x16, 0x16, 0x28));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        bottom.add(statsLabel, BorderLayout.WEST);
        bottom.add(buildLegend(), BorderLayout.EAST);

        frame.add(gridPanel, BorderLayout.CENTER);
        frame.add(bottom,    BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // 30 fps repaint timer
        new Timer(33, e -> tick()).start();
    }

    private void tick() {
        gridPanel.repaint();
        updateStats();
    }

    private JLabel buildStatsLabel() {
        JLabel lbl = new JLabel();
        lbl.setForeground(new Color(0xcc, 0xcc, 0xdd));
        lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return lbl;
    }

    private void updateStats() {
        Stats s   = world.stats;
        int food  = world.grid.countMatching(e -> e == Food.UNIT);
        statsLabel.setText(String.format(
            "<html><pre>Alive: %d  (A=%d  S=%d)   Food: %d   Births: %d   Deaths: %d   Meals: %d</pre></html>",
            s.totalAlive(), s.aliveAsexual.get(), s.aliveSexual.get(),
            food, s.births.sum(), s.deaths.sum(), s.meals.sum()));
    }

    private JPanel buildLegend() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        p.setBackground(new Color(0x16, 0x16, 0x28));
        addEntry(p, FOOD,     "Food");
        addEntry(p, ASEXUAL,  "Asexual");
        addEntry(p, SEXUAL,   "Sexual");
        addEntry(p, MATING,   "Seeking mate");
        addEntry(p, STARVING, "Starving");
        return p;
    }

    private void addEntry(JPanel panel, Color c, String label) {
        JPanel dot = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(c);
                g.fillOval(0, 0, getWidth(), getHeight());
            }
        };
        dot.setPreferredSize(new Dimension(12, 12));
        dot.setOpaque(false);
        JLabel lbl = new JLabel(label);
        lbl.setForeground(new Color(0xaa, 0xaa, 0xbb));
        lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        panel.add(dot);
        panel.add(lbl);
    }

    // -----------------------------------------------------------------------
    private final class GridPanel extends JPanel {
        GridPanel() {
            int w = world.config.width();
            int h = world.config.height();
            setPreferredSize(new Dimension(w * cellPx + 2, h * cellPx + 2));
            setBackground(BG);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int gw = world.config.width();
            int gh = world.config.height();
            long now        = System.nanoTime();
            long tStarveNs  = world.config.tStarveMs() * 1_000_000L;
            long tFullNs    = world.config.tFullMs()   * 1_000_000L;

            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    Entity e  = world.grid.get(world.grid.toIndex(x, y));
                    Color  c  = colorOf(e, now, tStarveNs, tFullNs);
                    int    px = x * cellPx;
                    int    py = y * cellPx;

                    if (e instanceof Cell) {
                        g2.setColor(c);
                        g2.fillOval(px + 1, py + 1, cellPx - 2, cellPx - 2);
                    } else if (e == Food.UNIT) {
                        int s   = Math.max(2, cellPx / 3);
                        int off = (cellPx - s) / 2;
                        g2.setColor(c);
                        g2.fillRect(px + off, py + off, s, s);
                    }
                    // empty tiles: background shows through
                }
            }
        }

        private Color colorOf(Entity e, long now, long tStarveNs, long tFullNs) {
            if (e == null)           return EMPTY;
            if (e == Food.UNIT)      return FOOD;
            if (e instanceof SexualCell sc)  return colorForCell(sc, sc.ready ? MATING : SEXUAL, now, tStarveNs, tFullNs);
            if (e instanceof AsexualCell ac) return colorForCell(ac, ASEXUAL, now, tStarveNs, tFullNs);
            return Color.WHITE;
        }

        private Color colorForCell(Cell c, Color base, long now, long tStarveNs, long tFullNs) {
            CellState st = c.state;
            if (st == CellState.HUNGRY) {
                long dl = c.hungerDeadlineNs;
                if (dl == 0) return base;
                // 1.0 = just started hungry, 0.0 = about to starve
                float frac = clamp((float)(dl - now) / tStarveNs);
                return lerp(STARVING, base, frac);
            }
            if (st == CellState.SATED) {
                long dl = c.satedDeadlineNs;
                if (dl == 0) return base;
                // 1.0 = just became sated, 0.0 = sated period almost over
                float frac = clamp((float)(dl - now) / tFullNs);
                return lerp(base.darker().darker(), base, frac);
            }
            return base;
        }

        private float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

        private Color lerp(Color a, Color b, float t) {
            return new Color(
                (int)(a.getRed()   + t * (b.getRed()   - a.getRed())),
                (int)(a.getGreen() + t * (b.getGreen() - a.getGreen())),
                (int)(a.getBlue()  + t * (b.getBlue()  - a.getBlue()))
            );
        }
    }
}
