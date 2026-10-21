package com.gameoflife;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the world, cell tasks, schedulers and statistics output. */
public class SimulationEngine {
    private final Config config;
    private final WorldMap worldMap;
    private final ExecutorService cellExecutor;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, Cell> registry = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final Object statsLock = new Object();
    private final Path statsFile;
    private volatile boolean statsClosed;
    private final long startTime = System.nanoTime();

    public record Config(long tFullMs, long tStarveMs, long matingTimeoutMs, long statsIntervalMs,
                         long foodGenerationIntervalMs, int foodGenerationAmount, int foodCapacity,
                         int initialFood, int initialSexualCells, int initialAsexualCells,
                         String statsFileName, int mapWidth, int mapHeight, int initialFoodCount,
                         int fieldOfView, long minMoveIntervalMs, long maxMoveIntervalMs,
                         int maxPopulation) {
        public Config {
            if (tFullMs < 0 || tStarveMs < 0 || statsIntervalMs <= 0
                    || foodGenerationIntervalMs < 0 || foodGenerationAmount < 0
                    || initialSexualCells < 0 || initialAsexualCells < 0) {
                throw new IllegalArgumentException("timings and population must be non-negative");
            }
            if (mapWidth <= 0 || mapHeight <= 0) throw new IllegalArgumentException("map dimensions must be positive");
            long capacity = (long) mapWidth * mapHeight;
            if (initialFoodCount < 0 || initialFoodCount > capacity) throw new IllegalArgumentException("invalid initial food count");
            if (fieldOfView < 0 || minMoveIntervalMs < 0 || maxMoveIntervalMs < minMoveIntervalMs) {
                throw new IllegalArgumentException("invalid movement settings");
            }
            if (maxPopulation < initialSexualCells + initialAsexualCells || maxPopulation > capacity) {
                throw new IllegalArgumentException("maxPopulation must fit the initial population and map");
            }
        }

        /** Compatibility constructor for the original non-spatial configuration. */
        public Config(long tFullMs, long tStarveMs, long matingTimeoutMs, long statsIntervalMs,
                      long foodGenerationIntervalMs, int foodGenerationAmount, int foodCapacity,
                      int initialFood, int initialSexualCells, int initialAsexualCells,
                      String statsFileName) {
            this(tFullMs, tStarveMs, matingTimeoutMs, statsIntervalMs, foodGenerationIntervalMs,
                    foodGenerationAmount, foodCapacity, initialFood, initialSexualCells,
                    initialAsexualCells, statsFileName, 20, 20, Math.min(initialFood, 400),
                    3, 1, 5, Math.max(100, initialSexualCells + initialAsexualCells));
        }
    }

    public SimulationEngine(Config config) {
        this.config = config;
        this.statsFile = Path.of(config.statsFileName());
        this.worldMap = new WorldMap(config.mapWidth(), config.mapHeight(), config.maxPopulation(),
                config.fieldOfView(), shutdown::get);
        this.cellExecutor = Executors.newFixedThreadPool(Math.max(1, config.maxPopulation()));
    }

    public void start() {
        initialize();
        writeStatsHeader();
        if (config.foodGenerationIntervalMs() > 0 && config.foodGenerationAmount() > 0) {
            scheduler.scheduleAtFixedRate(() -> worldMap.spawnFoodAtFreePositions(config.foodGenerationAmount()),
                    config.foodGenerationIntervalMs(), config.foodGenerationIntervalMs(), TimeUnit.MILLISECONDS);
        }
        scheduler.scheduleAtFixedRate(this::logStats, config.statsIntervalMs(),
                config.statsIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void initialize() {
        if (!registry.isEmpty()) return;
        List<Position> positions = new ArrayList<>();
        for (int x = 0; x < config.mapWidth(); x++) {
            for (int y = 0; y < config.mapHeight(); y++) positions.add(new Position(x, y));
        }
        Collections.shuffle(positions, ThreadLocalRandom.current());
        worldMap.spawnFoodAtFreePositions(config.initialFoodCount());
        int index = 0;
        for (int i = 0; i < config.initialSexualCells(); i++) {
            registerInitial(new SexualCell(this), positions.get(index++));
        }
        for (int i = 0; i < config.initialAsexualCells(); i++) {
            registerInitial(new AsexualCell(this), positions.get(index++));
        }
        worldMap.setInitialPopulation();
        for (Cell cell : registry.values()) cellExecutor.submit(cell);
    }

    private void registerInitial(Cell cell, Position position) {
        if (!worldMap.tryRegister(cell, position)) throw new IllegalStateException("failed to initialize cell");
        registry.put(cell.getId(), cell);
    }

    public void run(long durationMs) throws InterruptedException {
        start();
        Thread.sleep(durationMs);
        shutdown();
    }

    /** Runs the simulation without creating a graphical window. */
    public void runHeadless(long durationMs) throws InterruptedException {
        Thread.sleep(durationMs);
        shutdown();
    }

    void startOffspring(Cell cell) {
        registry.put(cell.getId(), cell);
        try {
            cellExecutor.submit(cell);
        } catch (RejectedExecutionException e) {
            registry.remove(cell.getId());
            worldMap.rollbackBirth(cell);
        }
    }

    /** Registers and starts a cell for compatibility with the original API. */
    public void submitCell(Cell cell) {
        List<Position> positions = new ArrayList<>();
        for (int x = 0; x < config.mapWidth(); x++) {
            for (int y = 0; y < config.mapHeight(); y++) positions.add(new Position(x, y));
        }
        Collections.shuffle(positions, ThreadLocalRandom.current());
        for (Position position : positions) {
            if (worldMap.tryRegister(cell, position)) {
                registry.put(cell.getId(), cell);
                worldMap.setInitialPopulation();
                startOffspring(cell);
                return;
            }
        }
        throw new IllegalStateException("no free position for cell");
    }

    Cell findCell(long id) { return registry.get(id); }
    public WorldMap getWorldMap() { return worldMap; }
    public boolean isShutdown() { return shutdown.get(); }
    public int getFieldOfView() { return config.fieldOfView(); }
    public long gettFullMs() { return config.tFullMs(); }
    public long gettStarveMs() { return config.tStarveMs(); }
    public long getMatingTimeoutMs() { return config.matingTimeoutMs(); }
    public long nextMoveIntervalMs() {
        if (config.minMoveIntervalMs() == config.maxMoveIntervalMs()) return config.minMoveIntervalMs();
        return ThreadLocalRandom.current().nextLong(config.minMoveIntervalMs(), config.maxMoveIntervalMs() + 1);
    }
    public int getPopulation() { return worldMap.snapshot().population(); }
    public int getBirths() { return Math.toIntExact(worldMap.snapshot().births()); }
    public int getDeaths() { return Math.toIntExact(worldMap.snapshot().deaths()); }
    public ResourceManager getResources() { return new ResourceManager(1, 0); }
    public MatingPool getMatingPool() { return new MatingPool(); }

    public void shutdown() throws InterruptedException {
        if (!shutdown.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
        cellExecutor.shutdownNow();
        cellExecutor.awaitTermination(2, TimeUnit.SECONDS);
        for (Cell cell : registry.values()) worldMap.tryKill(cell, "shutdown");
        registry.clear();
        logStats();
        synchronized (statsLock) { statsClosed = true; }
    }

    public void logStats() {
        WorldMap.WorldSnapshot snapshot = worldMap.snapshot();
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        String line = elapsedMs + "," + snapshot.population() + "," + snapshot.food() + ","
                + snapshot.births() + "," + snapshot.deaths() + "," + snapshot.meals();
        synchronized (statsLock) {
            if (statsClosed) return;
            try (BufferedWriter writer = Files.newBufferedWriter(statsFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            } catch (IOException e) {
                System.err.println("Failed to write statistics: " + e.getMessage());
            }
        }
    }

    private void writeStatsHeader() {
        synchronized (statsLock) {
            try (BufferedWriter writer = Files.newBufferedWriter(statsFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("timestamp_ms,population,food,births,deaths,meals");
                writer.newLine();
            } catch (IOException e) {
                System.err.println("Failed to write statistics header: " + e.getMessage());
            }
        }
    }
}
