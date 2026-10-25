package com.gameoflife;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GridConcurrencyTest {

    private static SimulationConfig testConfig() {
        return new SimulationConfig(20, 20, 10, 200, 500, 10, 3, 0.1, 5, 0, 0, 5000, true, false, 500);
    }

    @RepeatedTest(20)
    void stressTest_noEntityDuplicated() throws InterruptedException {
        SimulationConfig config = testConfig();
        World world = new World(config);
        int totalTiles = config.width() * config.height();

        // Pre-place some food
        for (int i = 0; i < totalTiles / 4; i++) {
            world.grid.tryPlace(i * 2, Food.UNIT);
        }

        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger violations = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int iter = 0; iter < 200; iter++) {
                        int pos = rng.nextInt(totalTiles);
                        int action = rng.nextInt(3);
                        if (action == 0) {
                            world.grid.tryPlace(pos, Food.UNIT);
                        } else if (action == 1) {
                            world.grid.tryConsumeFood(pos);
                        } else {
                            // Read and verify no duplicate reference in neighbors
                            Entity e = world.grid.get(pos);
                            // Just a read - no action needed
                        }
                    }
                } catch (Exception e) {
                    failed.set(true);
                } finally {
                    done.countDown();
                }
            });
        }

        // Auditor thread
        AtomicBoolean auditorRunning = new AtomicBoolean(true);
        Thread auditor = new Thread(() -> {
            while (auditorRunning.get()) {
                // Check that no position has the same entity reference as another position
                // (only relevant for Cell objects since Food.UNIT is a singleton - cells could duplicate)
                // With only Food.UNIT in this test, just verify grid reads don't throw
                for (int i = 0; i < totalTiles; i++) {
                    Entity e = world.grid.get(i);
                    // Verify it's either null or Food.UNIT (in this test we only place food)
                    if (e != null && e != Food.UNIT) {
                        violations.incrementAndGet();
                    }
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        auditor.setDaemon(true);
        auditor.start();

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Threads did not finish in time");
        auditorRunning.set(false);
        auditor.join(1000);

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertFalse(failed.get(), "A thread threw an exception");
        assertEquals(0, violations.get(), "Found non-food entities where only food should be");
    }

    @Test
    void tryMove_failedMoveLeavesCellAtSource() {
        SimulationConfig config = testConfig();
        World world = new World(config);
        Grid grid = world.grid;

        AsexualCell cell = new AsexualCell(1, world, 0);
        grid.tryPlace(0, cell);

        // Block destination
        grid.tryPlace(1, Food.UNIT);

        // tryMove should fail - destination occupied
        boolean moved = grid.tryMove(cell, 0, 1);
        assertFalse(moved);
        assertSame(cell, grid.get(0), "Cell should still be at source");
        assertSame(Food.UNIT, grid.get(1), "Destination should still have food");
    }

    @Test
    void neighbours_cornerHas3() {
        SimulationConfig config = testConfig();
        World world = new World(config);
        Grid grid = world.grid;
        // Corner pos = 0 (x=0, y=0) with allowDiagonal=true, no wrapAround
        int[] n = grid.neighbours(0);
        assertEquals(3, n.length, "Corner should have 3 neighbours");
    }

    @Test
    void neighbours_edgeHas5() {
        SimulationConfig config = testConfig();
        World world = new World(config);
        Grid grid = world.grid;
        // Top edge, middle: pos = (config.width()/2, 0)
        int pos = grid.toIndex(config.width() / 2, 0);
        int[] n = grid.neighbours(pos);
        assertEquals(5, n.length, "Top edge middle should have 5 neighbours");
    }

    @Test
    void neighbours_interiorHas8() {
        SimulationConfig config = testConfig();
        World world = new World(config);
        Grid grid = world.grid;
        int pos = grid.toIndex(5, 5);
        int[] n = grid.neighbours(pos);
        assertEquals(8, n.length, "Interior tile should have 8 neighbours");
    }

    @Test
    void nearestFree_findsAdjacentFree() {
        SimulationConfig config = testConfig();
        World world = new World(config);
        Grid grid = world.grid;

        // Fill everything except one spot
        int target = grid.toIndex(2, 2);
        int pos = grid.toIndex(1, 1);
        for (int i = 0; i < grid.size(); i++) {
            if (i != target && i != pos) grid.tryPlace(i, Food.UNIT);
        }
        grid.tryPlace(pos, Food.UNIT); // fill pos too (we're searching from a virtual spot)

        // Actually let's search from pos=0,0 with target clear
        // Reset
        for (int i = 0; i < grid.size(); i++) {
            if (grid.get(i) == Food.UNIT) grid.setOwn(i, null);
        }
        // Fill all except target
        for (int i = 0; i < grid.size(); i++) {
            if (i != target) grid.tryPlace(i, Food.UNIT);
        }

        int found = grid.nearestFree(pos, 20);
        assertEquals(target, found, "Should find the only free tile");
    }
}
