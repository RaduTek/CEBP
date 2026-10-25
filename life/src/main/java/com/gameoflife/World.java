package com.gameoflife;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public final class World {
    public final SimulationConfig config;
    public final Grid grid;
    public final Stats stats;
    public volatile boolean running = true;
    public final long startNs = System.nanoTime();

    private final ExecutorService cellExecutor;
    private final AtomicLong idGenerator = new AtomicLong(1);

    public World(SimulationConfig config) {
        this.config = config;
        this.grid = new Grid(config.width(), config.height(), config.allowDiagonal(), config.wrapAround());
        this.stats = new Stats();
        this.cellExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public long nextId() {
        return idGenerator.getAndIncrement();
    }

    public void spawn(Cell c) {
        cellExecutor.submit(c);
    }

    public void shutdownCells() {
        cellExecutor.shutdownNow();
    }

    public ExecutorService getCellExecutor() {
        return cellExecutor;
    }

    public boolean awaitCellTermination(long timeoutMs) throws InterruptedException {
        return cellExecutor.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
