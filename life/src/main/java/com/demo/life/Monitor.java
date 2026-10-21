package com.demo.life;

public final class Monitor implements Runnable {
    private final World world;
    private final boolean ansiClear;
    private final boolean suppressGrid; // true when a GUI is handling the visual render

    public Monitor(World world, boolean ansiClear) {
        this(world, ansiClear, false);
    }

    public Monitor(World world, boolean ansiClear, boolean suppressGrid) {
        this.world = world;
        this.ansiClear = ansiClear;
        this.suppressGrid = suppressGrid;
    }

    @Override
    public void run() {
        if (!world.running && world.stats.totalAlive() == 0) return;
        StringBuilder sb = new StringBuilder();
        world.stats.snapshot(sb);
        int foodCount = world.grid.countMatching(e -> e == Food.UNIT);
        sb.append("  Food=").append(foodCount).append('\n');
        if (!suppressGrid) {
            if (ansiClear) {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
            sb.append(GridRenderer.render(world.grid, false));
        }
        System.out.print(sb);
        System.out.flush();
    }
}
