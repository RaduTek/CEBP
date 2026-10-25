package com.gameoflife;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MatingHandshakeTest {

    private static SimulationConfig matingConfig() {
        return new SimulationConfig(10, 10, 5, 2000, 500, 1, 5, 0.0, 0, 0, 0, 60000, true, false, 500);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void handshake_exactlyOneChildPerMating() throws InterruptedException {
        SimulationConfig config = matingConfig();
        int iterations = 100_000;
        AtomicInteger totalChildren = new AtomicInteger(0);
        AtomicInteger failedRounds = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(4);

        for (int i = 0; i < iterations; i++) {
            World world = new World(config);
            Grid grid = world.grid;

            // Place two adjacent ready sexual cells
            int pos1 = grid.toIndex(4, 5);
            int pos2 = grid.toIndex(5, 5);

            SexualCell cell1 = new SexualCell(world.nextId(), world, pos1);
            SexualCell cell2 = new SexualCell(world.nextId(), world, pos2);

            grid.tryPlace(pos1, cell1);
            grid.tryPlace(pos2, cell2);

            cell1.state = CellState.SATED;
            cell2.state = CellState.SATED;
            cell1.ready = true;
            cell2.ready = true;
            cell1.mealsSinceReproduction = config.mealsToReproduce();
            cell2.mealsSinceReproduction = config.mealsToReproduce();
            world.stats.aliveSexual.set(2);

            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger childrenThisRound = new AtomicInteger(0);

            Future<?> f1 = pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (cell1.tryMate(cell2)) childrenThisRound.incrementAndGet();
            });
            Future<?> f2 = pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (cell2.tryMate(cell1)) childrenThisRound.incrementAndGet();
            });

            go.countDown();
            try { f1.get(5, TimeUnit.SECONDS); } catch (ExecutionException | TimeoutException e) { /* ok */ }
            try { f2.get(5, TimeUnit.SECONDS); } catch (ExecutionException | TimeoutException e) { /* ok */ }

            int children = childrenThisRound.get();
            // Can be 0 (both failed, e.g. no free tile) or 1 (one succeeded)
            // But NEVER 2
            assertTrue(children <= 1, "Round " + i + ": produced " + children + " children (max 1 allowed)");

            // Verify claimedBy is released
            assertEquals(0, cell1.claimedBy.get(), "cell1.claimedBy should be 0 after mating");
            assertEquals(0, cell2.claimedBy.get(), "cell2.claimedBy should be 0 after mating");

            totalChildren.addAndGet(children);
            if (children == 0) failedRounds.incrementAndGet();

            // Shutdown this world's cells
            world.running = false;
            world.shutdownCells();
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // Verify never produced 2 children per round (checked above)
        // Most rounds should produce exactly 1 child
        System.out.println("MatingHandshake: " + iterations + " rounds, " +
                totalChildren.get() + " total children, " +
                failedRounds.get() + " rounds with 0 children (no free tile or both failed CAS)");
        assertTrue(totalChildren.get() > iterations * 0.5, "Should succeed in at least 50% of rounds");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void crowdedMating_noCellInTwoMatingSimultaneously() throws InterruptedException {
        SimulationConfig config = matingConfig();
        World world = new World(config);
        Grid grid = world.grid;

        int n = 8;
        SexualCell[] cells = new SexualCell[n];
        for (int i = 0; i < n; i++) {
            int pos = grid.toIndex(i, 5);
            cells[i] = new SexualCell(world.nextId(), world, pos);
            grid.tryPlace(pos, cells[i]);
            cells[i].state = CellState.SATED;
            cells[i].ready = true;
            cells[i].mealsSinceReproduction = config.mealsToReproduce();
        }
        world.stats.aliveSexual.set(n);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger matingCount = new AtomicInteger(0);

        Future<?>[] futures = new Future[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                // Try to mate with each neighbour
                for (SexualCell other : cells) {
                    if (other == cells[idx]) continue;
                    if (cells[idx].tryMate(other)) {
                        matingCount.incrementAndGet();
                        break;
                    }
                }
            });
        }

        go.countDown();
        for (Future<?> f : futures) {
            try { f.get(10, TimeUnit.SECONDS); } catch (ExecutionException | TimeoutException e) { /* ok */ }
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // Verify all claimedBy slots returned to 0
        for (int i = 0; i < n; i++) {
            assertEquals(0, cells[i].claimedBy.get(), "Cell " + i + " claimedBy not released");
        }

        // Total births should equal successful mating pairs * 1 child each
        int births = (int) world.stats.births.sum();
        assertEquals(matingCount.get(), births, "Each successful mating should produce exactly 1 child");

        world.running = false;
        world.shutdownCells();
    }
}
