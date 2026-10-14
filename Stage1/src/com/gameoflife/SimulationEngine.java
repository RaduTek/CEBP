package com.gameoflife;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the simulation: owns the thread pool running the cells, the
 * shared resource pool, the mating pool, the registry of living cells and
 * the periodic statistics logger.
 */
public class SimulationEngine {

    private final long tFullMs;
    private final long tStarveMs;
    private final long matingTimeoutMs;
    private final long statsIntervalMs;
    private final long foodGenerationIntervalMs;
    private final int foodGenerationAmount;

    private final ResourceManager resources;
    private final MatingPool matingPool = new MatingPool();

    private final ExecutorService cellExecutor;
    private final ScheduledExecutorService scheduler;

    private final Map<Long, Cell> registry = new ConcurrentHashMap<>();
    private final AtomicInteger births = new AtomicInteger();
    private final AtomicInteger deaths = new AtomicInteger();
    private final AtomicLong mealCount = new AtomicLong();

    private final Path statsFile;
    private final long startTime = System.nanoTime();
    private volatile boolean shutdown;

    private final Config config;

    public SimulationEngine(Config config) {
        this.config = config;
        this.tFullMs = config.tFullMs();
        this.tStarveMs = config.tStarveMs();
        this.matingTimeoutMs = config.matingTimeoutMs();
        this.statsIntervalMs = config.statsIntervalMs();
        this.foodGenerationIntervalMs = config.foodGenerationIntervalMs();
        this.foodGenerationAmount = config.foodGenerationAmount();
        this.resources = new ResourceManager(config.foodCapacity(), config.initialFood());
        this.cellExecutor = Executors.newCachedThreadPool();
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
        start();
        for (int i = 0; i < config.initialSexualCells(); i++) {
            submitCell(new SexualCell(this));
        }
        for (int i = 0; i < config.initialAsexualCells(); i++) {
            submitCell(new AsexualCell(this));
        }
        Thread.sleep(durationMs);
        shutdown();
    }

    /** Registers and schedules a new cell task. */
    public void submitCell(Cell cell) {
        registry.put(cell.getId(), cell);
        births.incrementAndGet();
        cellExecutor.submit(cell);
    }

    public void unregisterCell(Cell cell) {
        registry.remove(cell.getId());
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

    public MatingPool getMatingPool() {
        return matingPool;
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

    public int getBirths() {
        return births.get();
    }

    public int getDeaths() {
        return deaths.get();
    }

    /** Stops food generation and stats, wakes mating cells, and shuts the pool down. */
    public void shutdown() throws InterruptedException {
        if (shutdown) {
            return;
        }
        shutdown = true;
        scheduler.shutdownNow();
        matingPool.shutdown();
        cellExecutor.shutdownNow();
        if (!cellExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            List<Runnable> dropped = cellExecutor.shutdownNow();
            System.err.println("Dropped " + dropped.size() + " unfinished cell tasks");
        }
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