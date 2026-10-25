package com.gameoflife;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SimulationEngine implements AutoCloseable {
    public static final long T_FULL_MS = 2_000;
    public static final long T_STARVE_MS = 3_000;
    private final ResourceManager resources;
    private final MatingPool matingPool = new MatingPool();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<Integer, Cell> activeCells = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicInteger births = new AtomicInteger();
    private final AtomicInteger deaths = new AtomicInteger();
    private volatile boolean running = true;

    public SimulationEngine(int initialFood) {
        resources = new ResourceManager(initialFood);
    }

    public void startInitialPopulation(int sexualCount, int asexualCount) {
        for (int i = 0; i < sexualCount; i++) {
            addCell(new SexualCell(nextId.getAndIncrement(), this));
        }
        for (int i = 0; i < asexualCount; i++) {
            addCell(new AsexualCell(nextId.getAndIncrement(), this));
        }
    }

    void addCell(Cell cell) {
        if (!running) {
            return;
        }
        activeCells.put(cell.getId(), cell);
        births.incrementAndGet();
        System.out.printf("Cell %d (%s) entered the simulation.%n", cell.getId(), cell.getType());
        executor.submit(cell);
    }

    void cellDied(Cell cell) {
        activeCells.remove(cell.getId());
        deaths.incrementAndGet();
        if (cell instanceof SexualCell) {
            matingPool.remove((SexualCell) cell);
        }
        int foodReleased = ThreadLocalRandom.current().nextInt(1, 6);
        resources.releaseFood(foodReleased);
        System.out.printf("Cell %d died and released %d food unit(s).%n", cell.getId(), foodReleased);
    }

    void reproduceAsexually(AsexualCell parent) {
        if (!running || !parent.isAlive()) return;
        // The parent is one of the two resulting cells; create one hungry offspring.
        addCell(new AsexualCell(nextId.getAndIncrement(), this));
        System.out.printf("Cell %d reproduced asexually.%n", parent.getId());
    }

    void reproduceSexually(SexualCell first, SexualCell second) {
        if (!running || !first.isAlive() || !second.isAlive()) return;
        first.resetMeals();
        second.resetMeals();
        addCell(new SexualCell(nextId.getAndIncrement(), this));
        System.out.printf("Cells %d and %d reproduced sexually.%n", first.getId(), second.getId());
    }

    ResourceManager getResources() { return resources; }
    MatingPool getMatingPool() { return matingPool; }
    boolean isRunning() { return running; }

    public void printStatistics() {
        System.out.printf("%n--- Simulation statistics ---%nActive cells: %d%nBirths: %d%nDeaths: %d%nFood available: %d%n",
                activeCells.size(), births.get(), deaths.get(), resources.getCurrentFoodCount());
    }

    @Override
    public void close() {
        running = false;
        for (Cell cell : activeCells.values()) {
            cell.stopCell();
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("Some cell tasks did not stop promptly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        printStatistics();
    }
}
