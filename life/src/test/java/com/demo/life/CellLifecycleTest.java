package com.demo.life;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CellLifecycleTest {

    private static SimulationConfig fastConfig() {
        return new SimulationConfig(10, 10, 5, 100, 300, 10, 3, 0.0, 0, 0, 0, 5000, true, false, 500);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cellWithFoodNearby_becomesSated() throws InterruptedException {
        // Cell should eat adjacent food and transition to SATED before starvation deadline
        SimulationConfig config = fastConfig();
        World world = new World(config);

        int cellPos = world.grid.toIndex(0, 0);
        AsexualCell cell = new AsexualCell(world.nextId(), world, cellPos);
        world.grid.tryPlace(cellPos, cell);
        world.stats.aliveAsexual.incrementAndGet();

        // Flood adjacent tiles with food so cell can eat on every hunger cycle
        int[] neighbours = world.grid.neighbours(cellPos);
        for (int n : neighbours) {
            world.grid.tryPlace(n, Food.UNIT);
        }

        world.spawn(cell);

        // Wait for cell to become SATED (i.e., it ate something)
        long deadline = System.currentTimeMillis() + config.tStarveMs() + 200;
        while (System.currentTimeMillis() < deadline && cell.state != CellState.SATED) {
            Thread.sleep(10);
        }

        assertEquals(CellState.SATED, cell.state, "Cell near food should become SATED after eating");

        world.running = false;
        world.shutdownCells();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cellWithNoFood_diesAtStarvation() throws InterruptedException {
        SimulationConfig config = fastConfig();
        World world = new World(config);

        int cellPos = world.grid.toIndex(5, 5);
        AsexualCell cell = new AsexualCell(world.nextId(), world, cellPos);
        world.grid.tryPlace(cellPos, cell);
        world.stats.aliveAsexual.incrementAndGet();

        world.spawn(cell);
        long deadline = System.currentTimeMillis() + config.tStarveMs() + 500;
        while (System.currentTimeMillis() < deadline && cell.state != CellState.DEAD) {
            Thread.sleep(20);
        }

        assertEquals(CellState.DEAD, cell.state, "Cell with no food should die");
        assertEquals(0, world.stats.aliveAsexual.get(), "alive counter should be 0 after death");

        world.running = false;
        world.shutdownCells();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cellDeath_dropsFood() throws InterruptedException {
        SimulationConfig config = fastConfig();
        World world = new World(config);

        int cellPos = world.grid.toIndex(5, 5);
        AsexualCell cell = new AsexualCell(world.nextId(), world, cellPos);
        world.grid.tryPlace(cellPos, cell);
        world.stats.aliveAsexual.incrementAndGet();

        world.spawn(cell);
        long deadline = System.currentTimeMillis() + config.tStarveMs() + 500;
        while (System.currentTimeMillis() < deadline && cell.state != CellState.DEAD) {
            Thread.sleep(20);
        }

        assertEquals(CellState.DEAD, cell.state);

        // Count food on grid: 1..5 units should appear
        int foodCount = world.grid.countMatching(e -> e == Food.UNIT);
        assertTrue(foodCount >= 1 && foodCount <= 5, "Death should drop 1-5 food units, got: " + foodCount);

        world.running = false;
        world.shutdownCells();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void hungrySatedCycle_timingCorrect() throws InterruptedException {
        // mealsToReproduce=100 so no reproduction occurs; cell just cycles hungry/sated
        SimulationConfig config = new SimulationConfig(
                10, 10, 5, 200, 500, 100, 5, 0.0, 0, 0, 0, 10000, true, false, 500
        );
        World world = new World(config);

        int cellPos = world.grid.toIndex(5, 5);
        // Flood grid with food so cell can always eat
        for (int i = 0; i < world.grid.size(); i++) {
            if (i != cellPos) world.grid.tryPlace(i, Food.UNIT);
        }

        AsexualCell cell = new AsexualCell(world.nextId(), world, cellPos);
        world.grid.tryPlace(cellPos, cell);
        world.stats.aliveAsexual.incrementAndGet();

        world.spawn(cell);

        // Wait for cell to become SATED
        long deadline = System.currentTimeMillis() + config.tStarveMs() + 200;
        while (System.currentTimeMillis() < deadline && cell.state != CellState.SATED) {
            Thread.sleep(10);
        }
        assertEquals(CellState.SATED, cell.state, "Cell should become sated after eating");

        long satedAt = System.currentTimeMillis();

        // Wait for sated phase to expire (cell transitions back to HUNGRY)
        deadline = System.currentTimeMillis() + config.tFullMs() + 300;
        while (System.currentTimeMillis() < deadline && cell.state == CellState.SATED) {
            Thread.sleep(10);
        }

        long elapsed = System.currentTimeMillis() - satedAt;
        assertNotEquals(CellState.DEAD, cell.state, "Cell should not die during sated phase");
        assertTrue(elapsed >= config.tFullMs() - 100,
                "Sated phase should last at least tFullMs=" + config.tFullMs() + "ms, lasted " + elapsed + "ms");

        world.running = false;
        world.shutdownCells();
    }
}
