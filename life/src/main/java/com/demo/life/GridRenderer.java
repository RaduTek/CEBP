package com.demo.life;

public final class GridRenderer {
    private static final String ANSI_CLEAR = "\033[H\033[2J";

    private GridRenderer() {}

    public static String render(Grid grid, boolean ansiClear) {
        StringBuilder sb = new StringBuilder();
        if (ansiClear) sb.append(ANSI_CLEAR);
        int w = grid.width();
        int h = grid.height();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Entity e = grid.get(grid.toIndex(x, y));
                if (e == null) sb.append('.');
                else if (e == Food.UNIT) sb.append('*');
                else if (e instanceof AsexualCell) sb.append('a');
                else if (e instanceof SexualCell) sb.append('s');
                else sb.append('?');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
