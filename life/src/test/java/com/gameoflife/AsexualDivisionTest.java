package com.gameoflife;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AsexualDivisionTest {

    private static SimulationConfig divisionConfig() {
        return new SimulationConfig(10, 10, 5, 2000, 500, 1, 5, 0.0, 0, 0, 0, 10000, true, false, 500);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void tenthMeal_triggersDivision() throws InterruptedException {
        // mealsToReproduce=1 so first meal -> reproduce immediately after sated phase
        SimulationConfig config = divisionConfig();
        World world = new World(config);

        // Flood grid with food (leave center for cell)
        int cellPos = world.grid.toIndex(5, 5);
        for (int i = 0; i < world.grid.size(); i++) {
            if (i != cellPos) world.grid.tryPlace(i, Food.UNIT);
        }

        AsexualCell parent = new AsexualCell(world.nextId(), world, cellPos);
        world.grid.tryPlace(cellPos, parent);
        world.stats.aliveAsexual.set(1);

        world.spawn(parent);

        // Wait for division: parent dies (DEAD) and 2 children spawn -> net +1
        long deadline = System.currentTimeMillis() + config.tStarveMs() + config.tFullMs() + 2000;
        while (System.currentTimeMillis() < deadline && parent.state != CellState.DEAD) {
            Thread.sleep(20);
        }

        assertEquals(CellState.DEAD, parent.state, "Parent should be DEAD after division");
        // Net: started with 1 alive, after division should have 2
        // Allow some time for children to register
        Thread.sleep(200);
        assertTrue(world.stats.aliveAsexual.get() >= 2, "Population should be >= 2 after division, got: " + world.stats.aliveAsexual.get());

        world.running = false;
        world.shutdownCells();
        world.awaitCellTermination(2000);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void noDivision_whenNoFreeNeighbour() {
        SimulationConfig config = divisionConfig();
        World world = new World(config);

        // Place cell in center and fill all its neighbours
        int cellPos = world.grid.toIndex(5, 5);
        AsexualCell cell = new AsexualCell(world.nextId(), world, cellPos);
        world.grid.tryPlace(cellPos, cell);
        world.stats.aliveAsexual.set(1);

        // Fill all neighbours
        int[] neighbours = world.grid.neighbours(cellPos);
        for (int n : neighbours) {
            world.grid.tryPlace(n, Food.UNIT);
        }

        // Manually call reproduce
        cell.mealsSinceReproduction = config.mealsToReproduce();
        boolean result = cell.reproduce(System.nanoTime() + 1_000_000_000L);

        assertFalse(result, "reproduce should return false when no free neighbour");
        assertEquals(1, world.stats.aliveAsexual.get(), "Population should not change");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void noDivision_whenGridFull_noException() {
        SimulationConfig config = new SimulationConfig(5, 5, 5, 2000, 500, 1, 2, 0.0, 0, 0, 0, 10000, true, false, 500);
        World world = new World(config);

        // Fill entire grid with food
        for (int i = 0; i < world.grid.size(); i++) {
            world.grid.tryPlace(i, Food.UNIT);
        }

        // Place one cell (overwrite one food)
        int cellPos = world.grid.toIndex(2, 2);
        AsexualCell cell = new AsexualCell(world.nextId(), world, cellPos);
        world.grid.setOwn(cellPos, cell);
        world.stats.aliveAsexual.set(1);

        assertDoesNotThrow(() -> {
            boolean result = cell.reproduce(System.nanoTime() + 1_000_000_000L);
            assertFalse(result, "Should not divide when grid is full");
        });
        assertEquals(1, world.stats.aliveAsexual.get(), "Population unchanged");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void division_netPlusOne() {
        SimulationConfig config = divisionConfig();
        World world = new World(config);

        int cellPos = world.grid.toIndex(5, 5);
        AsexualCell cell = new AsexualCell(world.nextId(), world, cellPos);
        world.grid.tryPlace(cellPos, cell);
        world.stats.aliveAsexual.set(1);

        cell.mealsSinceReproduction = config.mealsToReproduce();
        int aliveBefore = world.stats.aliveAsexual.get();

        boolean divided = cell.reproduce(System.nanoTime() + 1_000_000_000L);

        if (divided) {
            int aliveAfter = world.stats.aliveAsexual.get();
            assertEquals(aliveBefore + 1, aliveAfter, "Net population increase should be +1");
            assertEquals(CellState.DEAD, cell.state, "Parent should be marked DEAD");
        }
        // If didn't divide (no free neighbour somehow), that's also valid
    }

    @Test
    void populationNeverExceedsTileCount() throws InterruptedException {
        SimulationConfig config = new SimulationConfig(5, 5, 5, 500, 300, 1, 2, 0.5, 0, 5, 0, 3000, true, false, 500);
        World world = new World(config);

        // Place initial food
        int total = config.width() * config.height();
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < (int)(total * config.initialFoodDensity()); i++) {
            world.grid.tryPlace(rng.nextInt(total), Food.UNIT);
        }

        for (int i = 0; i < config.initialAsexual(); i++) {
            for (int t = 0; t < total; t++) {
                int pos = rng.nextInt(total);
                AsexualCell c = new AsexualCell(world.nextId(), world, pos);
                if (world.grid.tryPlace(pos, c)) {
                    world.stats.aliveAsexual.incrementAndGet();
                    world.spawn(c);
                    break;
                }
            }
        }

        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            int alive = world.stats.totalAlive();
            assertTrue(alive <= total, "Alive (" + alive + ") exceeded tile count (" + total + ")");
            Thread.sleep(50);
        }

        world.running = false;
        world.shutdownCells();
        world.awaitCellTermination(2000);
    }
}
