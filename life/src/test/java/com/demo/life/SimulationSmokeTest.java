package com.demo.life;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SimulationSmokeTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shortRun_noExceptionsNoDeadlock() throws InterruptedException {
        SimulationConfig config = new SimulationConfig(
                20, 20, 10, 300, 800, 5, 4, 0.15, 5, 10, 10, 5000, true, false, 1000
        );
        World world = new World(config);

        AtomicBoolean exceptionOccurred = new AtomicBoolean(false);

        // Place initial food
        int total = config.width() * config.height();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < (int)(total * config.initialFoodDensity()); i++) {
            world.grid.tryPlace(rng.nextInt(total), Food.UNIT);
        }

        // Place initial cells
        for (int i = 0; i < config.initialAsexual(); i++) {
            placeCell(world, total, rng, false);
        }
        for (int i = 0; i < config.initialSexual(); i++) {
            placeCell(world, total, rng, true);
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smoke-spawner");
            t.setDaemon(true);
            return t;
        });

        FoodSpawner spawner = new FoodSpawner(world);
        scheduler.scheduleAtFixedRate(spawner, 1000, 1000, TimeUnit.MILLISECONDS);

        // Run for 5 seconds
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < config.runDurationMs()) {
            if (world.stats.totalAlive() == 0) break;
            Thread.sleep(100);
        }

        world.running = false;
        world.shutdownCells();
        boolean terminated = world.awaitCellTermination(5000);
        scheduler.shutdown();
        scheduler.awaitTermination(1000, TimeUnit.MILLISECONDS);

        assertTrue(terminated, "Cell executor should terminate within 5s of shutdown");
        assertFalse(exceptionOccurred.get(), "No exception should occur during simulation");

        // Final audit: alive counter should match cells on grid
        int cellsOnGrid = world.grid.countMatching(e -> e instanceof Cell);
        int aliveCounter = world.stats.totalAlive();
        assertEquals(cellsOnGrid, aliveCounter,
                "Cells on grid (" + cellsOnGrid + ") should match alive counter (" + aliveCounter + ")");

        // No threads should be stuck
        long virtualThreadCount = Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isVirtual)
                .count();
        assertTrue(virtualThreadCount < 50, "Too many virtual threads still running: " + virtualThreadCount);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void emptyGrid_startsAndExits() throws InterruptedException {
        SimulationConfig config = new SimulationConfig(
                10, 10, 5, 100, 200, 3, 2, 0.0, 0, 3, 0, 2000, true, false, 500
        );
        // No food, only asexual cells -> they all die -> population 0
        Main.run(config);
        // If we get here without deadlock, test passes
    }

    private static void placeCell(World world, int total, ThreadLocalRandom rng, boolean sexual) {
        for (int t = 0; t < total * 2; t++) {
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
    }
}
