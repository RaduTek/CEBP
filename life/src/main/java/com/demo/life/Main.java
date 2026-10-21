package com.demo.life;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class Main {
    public static void main(String[] args) throws InterruptedException {
        Args parsed = parseArgs(args);
        run(parsed.config, parsed.gui, parsed.csvPath);
    }

    public static void run(SimulationConfig config, boolean gui, String csvPath) throws InterruptedException {
        World world = new World(config);
        placeInitialFood(world);
        placeInitialCells(world);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-scheduler");
            t.setDaemon(true);
            return t;
        });

        FoodSpawner spawner = new FoodSpawner(world);
        scheduler.scheduleAtFixedRate(spawner, 1000, 1000, TimeUnit.MILLISECONDS);

        // CSV logger
        CsvStatsLogger csvLogger = null;
        if (csvPath != null) {
            try {
                csvLogger = new CsvStatsLogger(world, csvPath);
                CsvStatsLogger finalCsvLogger = csvLogger;
                scheduler.scheduleAtFixedRate(finalCsvLogger, 0, config.statsIntervalMs(), TimeUnit.MILLISECONDS);
                System.out.println("Logging stats to: " + csvPath);
            } catch (IOException ex) {
                System.err.println("Cannot open CSV file '" + csvPath + "': " + ex.getMessage());
            }
        }

        // Swing GUI
        if (gui) {
            SwingGridView view = new SwingGridView(world);
            view.start();
        }

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < config.runDurationMs()) {
            if (world.stats.totalAlive() == 0) {
                System.out.println("Population extinct.");
                break;
            }
            Thread.sleep(200);
        }

        world.running = false;
        world.shutdownCells();
        world.awaitCellTermination(5000);
        scheduler.shutdown();
        scheduler.awaitTermination(2000, TimeUnit.MILLISECONDS);
        if (csvLogger != null) csvLogger.close();

        System.out.println("\n=== Final Stats ===");
        StringBuilder sb = new StringBuilder();
        world.stats.snapshot(sb);
        System.out.println(sb);
        int foodCount = world.grid.countMatching(e -> e == Food.UNIT);
        System.out.println("Food on grid: " + foodCount);
        System.out.println("Grid audit: " + auditGrid(world));
    }

    // Legacy entry point used by tests (no GUI, no CSV)
    public static void run(SimulationConfig config) throws InterruptedException {
        run(config, false, null);
    }

    private static void placeInitialFood(World world) {
        SimulationConfig cfg = world.config;
        int total = cfg.width() * cfg.height();
        int count = (int) (total * cfg.initialFoodDensity());
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int placed = 0;
        int tries = 0;
        while (placed < count && tries < count * 10) {
            int pos = rng.nextInt(total);
            if (world.grid.tryPlace(pos, Food.UNIT)) placed++;
            tries++;
        }
    }

    private static void placeInitialCells(World world) {
        SimulationConfig cfg = world.config;
        int total = cfg.width() * cfg.height();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int i = 0; i < cfg.initialAsexual(); i++) {
            placeCell(world, total, rng, false);
        }
        for (int i = 0; i < cfg.initialSexual(); i++) {
            placeCell(world, total, rng, true);
        }
    }

    private static void placeCell(World world, int total, ThreadLocalRandom rng, boolean sexual) {
        for (int tries = 0; tries < total * 2; tries++) {
            int pos = rng.nextInt(total);
            Cell cell = sexual
                    ? new SexualCell(world.nextId(), world, pos)
                    : new AsexualCell(world.nextId(), world, pos);
            if (world.grid.tryPlace(pos, cell)) {
                if (sexual) world.stats.aliveSexual.incrementAndGet();
                else world.stats.aliveAsexual.incrementAndGet();
                world.spawn(cell);
                return;
            }
        }
        System.err.println("Warning: could not place initial cell (grid too full?)");
    }

    private static String auditGrid(World world) {
        int cellsOnGrid = world.grid.countMatching(e -> e instanceof Cell);
        int alive = world.stats.totalAlive();
        if (cellsOnGrid == alive) return "PASS (cells on grid=" + cellsOnGrid + " matches alive counter)";
        return "FAIL (cells on grid=" + cellsOnGrid + " but alive counter=" + alive + ")";
    }

    private record Args(SimulationConfig config, boolean gui, String csvPath) {}

    private static Args parseArgs(String[] args) {
        int width = 40, height = 20;
        long moveDelayMs = 30, tFullMs = 500, tStarveMs = 1500, runDurationMs = 60000, statsIntervalMs = 1000;
        int mealsToReproduce = 10, visionRadius = 6, foodSpawnPerSecond = 15, initialAsexual = 20, initialSexual = 20;
        double initialFoodDensity = 0.15;
        boolean allowDiagonal = true, wrapAround = false;
        boolean gui = true;
        String csvPath = "simulation_stats.csv";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--width"             -> width              = Integer.parseInt(args[++i]);
                case "--height"            -> height             = Integer.parseInt(args[++i]);
                case "--move-delay"        -> moveDelayMs        = Long.parseLong(args[++i]);
                case "--t-full"            -> tFullMs            = Long.parseLong(args[++i]);
                case "--t-starve"          -> tStarveMs          = Long.parseLong(args[++i]);
                case "--meals-to-reproduce"-> mealsToReproduce   = Integer.parseInt(args[++i]);
                case "--vision-radius"     -> visionRadius       = Integer.parseInt(args[++i]);
                case "--food-density"      -> initialFoodDensity = Double.parseDouble(args[++i]);
                case "--food-spawn"        -> foodSpawnPerSecond = Integer.parseInt(args[++i]);
                case "--initial-asexual"   -> initialAsexual     = Integer.parseInt(args[++i]);
                case "--initial-sexual"    -> initialSexual      = Integer.parseInt(args[++i]);
                case "--run-duration"      -> runDurationMs      = Long.parseLong(args[++i]);
                case "--stats-interval"    -> statsIntervalMs    = Long.parseLong(args[++i]);
                case "--no-diagonal"       -> allowDiagonal      = false;
                case "--wrap"              -> wrapAround         = true;
                case "--no-gui"            -> gui                = false;
                case "--csv"               -> csvPath            = args[++i];
                case "--no-csv"            -> csvPath            = null;
            }
        }

        SimulationConfig config = new SimulationConfig(width, height, moveDelayMs, tFullMs, tStarveMs,
                mealsToReproduce, visionRadius, initialFoodDensity, foodSpawnPerSecond, initialAsexual,
                initialSexual, runDurationMs, allowDiagonal, wrapAround, statsIntervalMs);
        return new Args(config, gui, csvPath);
    }
}
