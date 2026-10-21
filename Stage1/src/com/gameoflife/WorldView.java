package com.gameoflife;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

/** Lightweight Swing renderer that paints immutable map frames. */
public final class WorldView extends JPanel {
    private static final long serialVersionUID = 1L;
    private final transient SimulationEngine engine;

    public WorldView(SimulationEngine engine) {
        this.engine = engine;
        setPreferredSize(new Dimension(1000, 620));
        setBackground(new Color(18, 27, 37));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        WorldMap.RenderSnapshot snapshot = engine.getWorldMap().renderSnapshot();
        int width = snapshot.width();
        int height = snapshot.height();
        double tileW = (double) getWidth() / width;
        double tileH = (double) getHeight() / height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = (int) (x * tileW);
                int py = (int) (y * tileH);
                g.setColor(new Color(31, 43, 54));
                g.drawRect(px, py, (int) Math.ceil(tileW), (int) Math.ceil(tileH));

                if (snapshot.food().contains(new Position(x, y))) {
                    int amount = 1;
                    int diameter = Math.min(8 + amount * 2, (int) Math.min(tileW, tileH) - 4);
                    if (diameter > 0) {
                        g.setColor(new Color(105, 214, 129));
                        g.fillOval(px + ((int) tileW - diameter) / 2,
                                py + ((int) tileH - diameter) / 2, diameter, diameter);
                        if (amount > 1) {
                            g.setColor(new Color(14, 36, 24));
                            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
                            g.drawString(Integer.toString(amount), px + 2, py + 11);
                        }
                    }
                }
            }
        }

        Map<Integer, Integer> stacked = new HashMap<>();
        for (WorldMap.CellView cell : snapshot.cells()) {
            int x = cell.position().x();
            int y = cell.position().y();
            int offset = stacked.merge(y * width + x, 1, Integer::sum) - 1;
            int diameter = Math.max(8, (int) Math.min(tileW, tileH) - 5);
            int px = (int) (x * tileW + (tileW - diameter) / 2) + offset * 2;
            int py = (int) (y * tileH + (tileH - diameter) / 2) + offset * 2;
            Color color = cell.type() == CellType.SEXUAL
                    ? new Color(105, 170, 255)
                    : new Color(255, 172, 79);
            if (cell.state() == CellState.HUNGRY) color = color.darker();
            if (cell.state() == CellState.REPRODUCING) color = new Color(229, 119, 239);
            g.setColor(color);
            g.fillOval(px, py, diameter, diameter);
            g.setColor(new Color(9, 20, 31));
            g.drawOval(px, py, diameter, diameter);
        }
        g.dispose();
    }
}
