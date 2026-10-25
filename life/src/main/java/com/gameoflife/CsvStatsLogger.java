package com.gameoflife;

import java.io.*;

public final class CsvStatsLogger implements Runnable, Closeable {
    private final World world;
    private final PrintWriter writer;
    private final long startMs = System.currentTimeMillis();

    public CsvStatsLogger(World world, String path) throws IOException {
        this.world  = world;
        this.writer = new PrintWriter(new BufferedWriter(new FileWriter(path, false)));
        writer.println("time_ms,alive_asexual,alive_sexual,alive_total,births,deaths,meals,food_on_grid");
        writer.flush();
    }

    @Override
    public void run() {
        long elapsed = System.currentTimeMillis() - startMs;
        Stats s  = world.stats;
        int food = world.grid.countMatching(e -> e == Food.UNIT);
        writer.printf("%d,%d,%d,%d,%d,%d,%d,%d%n",
            elapsed,
            s.aliveAsexual.get(), s.aliveSexual.get(), s.totalAlive(),
            s.births.sum(), s.deaths.sum(), s.meals.sum(), food);
        writer.flush();
    }

    @Override
    public void close() {
        writer.close();
    }
}
