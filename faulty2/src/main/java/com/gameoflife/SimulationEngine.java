package com.gameoflife;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the simulation: owns the map resources, cell registry and
 * periodic statistics logger. Every live cell has a dedicated thread.
 */
public class SimulationEngine {

    private final long tFullMs;
    private final long tStarveMs;
    private final long matingTimeoutMs;
    private final long statsIntervalMs;
    private final long foodGenerationIntervalMs;
    private final int foodGenerationAmount;

    private final ResourceManager resources;
    private final ScheduledExecutorService scheduler;

    private final Map<Long, Cell> registry = new ConcurrentHashMap<>();
    private final Map<Long, Thread> cellThreads = new ConcurrentHashMap<>();
    private final AtomicInteger births = new AtomicInteger();
    private final AtomicInteger deaths = new AtomicInteger();
    private final AtomicLong mealCount = new AtomicLong();

    private final Path statsFile;
    private final long startTime = System.nanoTime();
    private volatile boolean shutdown;

    private final Config config;
    private static final int WORLD_WIDTH = 48;
    private static final int WORLD_HEIGHT = 30;

    public SimulationEngine(Config config) {
        this.config = config;
        this.tFullMs = config.tFullMs();
        this.tStarveMs = config.tStarveMs();
        this.matingTimeoutMs = config.matingTimeoutMs();
        this.statsIntervalMs = config.statsIntervalMs();
        this.foodGenerationIntervalMs = config.foodGenerationIntervalMs();
        this.foodGenerationAmount = config.foodGenerationAmount();
        this.resources = new ResourceManager(WORLD_WIDTH, WORLD_HEIGHT, config.foodCapacity(), config.initialFood());
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.statsFile = Path.of(config.statsFileName());
    }

    /** Immutable simulation configuration. */
    public record Config(
            long tFullMs,
            long tStarveMs,
            long matingTimeoutMs,
            long statsIntervalMs,
            long foodGenerationIntervalMs,
            int foodGenerationAmount,
            int foodCapacity,
            int initialFood,
            int initialSexualCells,
            int initialAsexualCells,
            String statsFileName) {
    }

    /** Starts the periodic food generation and statistics logging. */
    public void start() {
        if (foodGenerationIntervalMs > 0 && foodGenerationAmount > 0) {
            scheduler.scheduleAtFixedRate(
                    () -> resources.addFood(foodGenerationAmount),
                    foodGenerationIntervalMs, foodGenerationIntervalMs, TimeUnit.MILLISECONDS);
        }
        scheduler.scheduleAtFixedRate(this::logStats, statsIntervalMs, statsIntervalMs, TimeUnit.MILLISECONDS);
        writeStatsHeader();
    }

    /** Submits the initial population and runs until {@code durationMs} elapse. */
    public void run(long durationMs) throws InterruptedException {
        startSimulation();
        Thread.sleep(durationMs);
        shutdown();
    }

    public void startSimulation() {
        start();
        for (int i = 0; i < config.initialSexualCells(); i++) submitCell(new SexualCell(this));
        for (int i = 0; i < config.initialAsexualCells(); i++) submitCell(new AsexualCell(this));
    }

    public List<Cell> getCellsSnapshot() { return List.copyOf(registry.values()); }

    /** Registers and schedules a new cell task. */
    public synchronized void submitCell(Cell cell) {
        if (shutdown) return;
        registry.put(cell.getId(), cell);
        births.incrementAndGet();
        Thread thread = new Thread(cell, "cell-" + cell.getId());
        cellThreads.put(cell.getId(), thread);
        thread.start();
    }

    public void unregisterCell(Cell cell) {
        registry.remove(cell.getId());
        cellThreads.remove(cell.getId());
    }

    public void recordDeath() {
        deaths.incrementAndGet();
    }

    public void recordMeal() {
        mealCount.incrementAndGet();
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public ResourceManager getResources() {
        return resources;
    }

    public long gettFullMs() {
        return tFullMs;
    }

    public long gettStarveMs() {
        return tStarveMs;
    }

    public long getMatingTimeoutMs() {
        return matingTimeoutMs;
    }

    public int getPopulation() {
        return registry.size();
    }

    public int getWidth() { return WORLD_WIDTH; }
    public int getHeight() { return WORLD_HEIGHT; }
    public long gettStarveNanos() { return TimeUnit.MILLISECONDS.toNanos(tStarveMs); }
    public long getMatingTimeoutNanos() { return TimeUnit.MILLISECONDS.toNanos(matingTimeoutMs); }
    public int randomX() { return ThreadLocalRandom.current().nextInt(WORLD_WIDTH); }
    public int randomY() { return ThreadLocalRandom.current().nextInt(WORLD_HEIGHT); }
    public int[] getFoodSnapshot() { return resources.snapshot(); }
    public boolean tryEatAt(int x, int y) { return resources.tryConsumeAt(x, y); }
    public void dropFoodAt(int x, int y, int amount) { resources.addFoodAt(x, y, amount); }

    /** Candidate search is lock-free; only the final two-cell claim is synchronized. */
    public boolean tryPairNearby(Cell self) {
        for (Cell other : registry.values()) {
            if (other == self || other.getType() != CellType.SEXUAL || !other.isSeekingMate()) continue;
            if (Math.max(Math.abs(self.getX() - other.getX()), Math.abs(self.getY() - other.getY())) > 1) continue;
            Cell first = self.getId() < other.getId() ? self : other;
            Cell second = first == self ? other : self;
            synchronized (first) {
                synchronized (second) {
                    if (self.pairWith(other)) return true;
                }
            }
        }
        return false;
    }

    public int getBirths() {
        return births.get();
    }

    public long getMeals() { return mealCount.get(); }

    public int getDeaths() {
        return deaths.get();
    }

    /** Stops food generation and stats, wakes mating cells, and shuts the pool down. */
    public void shutdown() throws InterruptedException {
        synchronized (this) {
            if (shutdown) return;
            shutdown = true;
        }
        scheduler.shutdownNow();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
        List<Thread> running = List.copyOf(cellThreads.values());
        for (Thread thread : running) thread.interrupt();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (Thread thread : running) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }
        registry.clear();
        cellThreads.clear();
        logStats();
    }

    /** Appends one CSV row with the current statistics. */
    public void logStats() {
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        String line = elapsedMs + "," + getPopulation() + "," + resources.getAvailableFood()
                + "," + births.get() + "," + deaths.get() + "," + mealCount.get();
        try (BufferedWriter writer = Files.newBufferedWriter(statsFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("Failed to write statistics: " + e.getMessage());
        }
    }

    private void writeStatsHeader() {
        try (BufferedWriter writer = Files.newBufferedWriter(statsFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("timestamp_ms,population,food,births,deaths,meals");
            writer.newLine();
        } catch (IOException e) {
            System.err.println("Failed to write statistics header: " + e.getMessage());
        }
    }
}
