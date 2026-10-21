package com.demo.life;

public final class Monitor implements Runnable {
    private final World world;
    private final boolean ansiClear;

    public Monitor(World world, boolean ansiClear) {
        this.world = world;
        this.ansiClear = ansiClear;
    }

    @Override
    public void run() {
        if (!world.running && world.stats.totalAlive() == 0) return;
        StringBuilder sb = new StringBuilder();
        world.stats.snapshot(sb);
        int foodCount = world.grid.countMatching(e -> e == Food.UNIT);
        sb.append("  Food=").append(foodCount).append('\n');
        sb.append(GridRenderer.render(world.grid, false));
        if (ansiClear) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        }
        System.out.print(sb);
        System.out.flush();
    }
}
