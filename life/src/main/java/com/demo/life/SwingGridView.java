package com.demo.life;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public final class SwingGridView {
    private static final int INIT_CELL = 18; // initial tile px (window resizes freely after)
    private static final int MIN_CELL  = 2;

    // Dark-theme palette
    private static final Color BG       = new Color(0x0d, 0x11, 0x17);
    private static final Color EMPTY    = new Color(0x1e, 0x1e, 0x2e);
    private static final Color FOOD     = new Color(0x00, 0xd2, 0x80);
    private static final Color ASEXUAL  = new Color(0x4a, 0x9e, 0xff);
    private static final Color SEXUAL   = new Color(0xff, 0xa0, 0x30);
    private static final Color MATING   = new Color(0xff, 0x5c, 0xa8);
    private static final Color STARVING = new Color(0xff, 0x3c, 0x3c);

    private static final Color[] LEGEND_COLORS  = { FOOD, ASEXUAL, SEXUAL, MATING, STARVING };
    private static final String[] LEGEND_LABELS = { "Food", "Asexual", "Sexual", "Seeking mate", "Starving" };

    private final World world;

    public SwingGridView(World world) {
        this.world = world;
    }

    public void start() {
        SwingUtilities.invokeLater(this::buildUI);
    }

    private void buildUI() {
        JFrame frame = new JFrame("Game of Life — Concurrency Demo");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { world.running = false; }
        });

        GridPanel  grid  = new GridPanel();
        StatsPanel stats = new StatsPanel();

        frame.setLayout(new BorderLayout());
        frame.add(grid,  BorderLayout.CENTER);
        frame.add(stats, BorderLayout.SOUTH);
        frame.setMinimumSize(new Dimension(320, 200));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        new Timer(33, e -> { grid.repaint(); stats.repaint(); }).start();
    }

    // -----------------------------------------------------------------------
    // Grid panel — cell size computed from current bounds each frame
    // -----------------------------------------------------------------------
    private final class GridPanel extends JPanel {
        GridPanel() {
            setBackground(BG);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(
                world.config.width()  * INIT_CELL,
                world.config.height() * INIT_CELL);
        }

        /** Tile size that fills the panel while keeping cells square. */
        private int cellPx() {
            int gw = world.config.width();
            int gh = world.config.height();
            return Math.max(MIN_CELL, Math.min(getWidth() / gw, getHeight() / gh));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int cp          = cellPx();
            int gw          = world.config.width();
            int gh          = world.config.height();
            long now        = System.nanoTime();
            long tStarveNs  = world.config.tStarveMs() * 1_000_000L;
            long tFullNs    = world.config.tFullMs()   * 1_000_000L;

            // Centre the grid inside the panel
            int offX = (getWidth()  - gw * cp) / 2;
            int offY = (getHeight() - gh * cp) / 2;

            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    Entity e  = world.grid.get(world.grid.toIndex(x, y));
                    Color  c  = colorOf(e, now, tStarveNs, tFullNs);
                    int    px = offX + x * cp;
                    int    py = offY + y * cp;

                    if (e instanceof Cell) {
                        g2.setColor(c);
                        g2.fillOval(px + 1, py + 1, cp - 2, cp - 2);
                    } else if (e == Food.UNIT) {
                        int s   = Math.max(1, cp / 3);
                        int off = (cp - s) / 2;
                        g2.setColor(c);
                        g2.fillRect(px + off, py + off, s, s);
                    }
                }
            }
        }

        private Color colorOf(Entity e, long now, long tStarveNs, long tFullNs) {
            if (e == null)                   return EMPTY;
            if (e == Food.UNIT)              return FOOD;
            if (e instanceof SexualCell sc)  return colorForCell(sc, sc.ready ? MATING : SEXUAL, now, tStarveNs, tFullNs);
            if (e instanceof AsexualCell ac) return colorForCell(ac, ASEXUAL, now, tStarveNs, tFullNs);
            return Color.WHITE;
        }

        private Color colorForCell(Cell c, Color base, long now, long tStarveNs, long tFullNs) {
            if (c.state == CellState.HUNGRY) {
                long dl = c.hungerDeadlineNs;
                if (dl == 0) return base;
                return lerp(STARVING, base, clamp((float)(dl - now) / tStarveNs));
            }
            if (c.state == CellState.SATED) {
                long dl = c.satedDeadlineNs;
                if (dl == 0) return base;
                return lerp(base.darker().darker(), base, clamp((float)(dl - now) / tFullNs));
            }
            return base;
        }
    }

    // -----------------------------------------------------------------------
    // Stats + legend panel — everything scaled to current panel height
    // -----------------------------------------------------------------------
    private final class StatsPanel extends JPanel {
        StatsPanel() {
            setBackground(new Color(0x16, 0x16, 0x28));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(100, 48);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);

            int ph       = getHeight();
            int pw       = getWidth();
            int fontSize = Math.max(8, Math.min(15, ph * 28 / 100)); // ~28% of panel height
            Font font    = new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();

            int baseline = (ph + fm.getAscent() - fm.getDescent()) / 2;

            // --- Stats text (left) ---
            g2.setColor(new Color(0xcc, 0xcc, 0xdd));
            Stats s   = world.stats;
            int food  = world.grid.countMatching(e -> e == Food.UNIT);
            String txt = String.format(
                "Alive: %d  (A=%d  S=%d)   Food: %d   Births: %d   Deaths: %d   Meals: %d",
                s.totalAlive(), s.aliveAsexual.get(), s.aliveSexual.get(),
                food, s.births.sum(), s.deaths.sum(), s.meals.sum());
            g2.drawString(txt, fontSize, baseline);

            // --- Legend (right) ---
            int swatchSz = Math.max(6, fontSize);
            int gap      = Math.max(4, fontSize / 2);
            int textGap  = Math.max(3, fontSize / 3);

            // Measure total legend width to right-align
            int legendW = 0;
            int[] tw = new int[LEGEND_LABELS.length];
            for (int i = 0; i < LEGEND_LABELS.length; i++) {
                tw[i]    = fm.stringWidth(LEGEND_LABELS[i]);
                legendW += swatchSz + textGap + tw[i] + gap;
            }

            int x = pw - legendW - fontSize;
            for (int i = 0; i < LEGEND_LABELS.length; i++) {
                // swatch
                g2.setColor(LEGEND_COLORS[i]);
                int sy = (ph - swatchSz) / 2;
                g2.fillOval(x, sy, swatchSz, swatchSz);
                // label
                g2.setColor(new Color(0xaa, 0xaa, 0xbb));
                g2.drawString(LEGEND_LABELS[i], x + swatchSz + textGap, baseline);
                x += swatchSz + textGap + tw[i] + gap;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Colour helpers
    // -----------------------------------------------------------------------
    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static Color lerp(Color a, Color b, float t) {
        return new Color(
            (int)(a.getRed()   + t * (b.getRed()   - a.getRed())),
            (int)(a.getGreen() + t * (b.getGreen() - a.getGreen())),
            (int)(a.getBlue()  + t * (b.getBlue()  - a.getBlue())));
    }
}
