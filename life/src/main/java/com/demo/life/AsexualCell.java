package com.demo.life;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;

public final class AsexualCell extends Cell {

    public AsexualCell(long id, World world, int pos) {
        super(id, world, pos);
    }

    @Override
    protected boolean reproduce(long satedDeadline) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int[] neighbours = world.grid.neighbours(pos);
        shuffleInts(neighbours, rng);

        // Create both children and claim tiles
        AsexualCell child1 = new AsexualCell(world.nextId(), world, pos);
        AsexualCell child2 = null;
        int child2Pos = -1;

        for (int n : neighbours) {
            AsexualCell candidate = new AsexualCell(world.nextId(), world, n);
            if (world.grid.tryPlace(n, candidate)) {
                child2 = candidate;
                child2Pos = n;
                break;
            }
        }

        if (child2 == null) return false;

        // Replace own tile with child1 (parent owns its own tile)
        world.grid.setOwn(pos, child1);

        // Net +1 alive: parent (1) -> child1 (1) + child2 (1) = net +1
        world.stats.aliveAsexual.incrementAndGet();
        world.stats.births.add(2);

        try {
            world.spawn(child1);
            world.spawn(child2);
        } catch (RejectedExecutionException e) {
            // Roll back
            world.grid.setOwn(pos, null);
            world.grid.setOwn(child2Pos, null);
            world.stats.aliveAsexual.decrementAndGet();
            world.stats.births.add(-2);
            return false;
        }

        // Parent exits: mark DEAD to prevent finally block in run() from double-clearing
        state = CellState.DEAD;
        EventLog.log(world, String.format("A#%d DIVIDED  → A#%d, A#%d  (alive=%d)",
            id, child1.id, child2.id, world.stats.totalAlive()));
        return true;
    }

    @Override
    protected void afterReproduction() {
        // Asexual parent just dies (no tile to clear - tile now belongs to child1)
        // The run() loop will return after this
    }

    @Override
    protected void wanderOrSeekMate(ThreadLocalRandom rng) {
        int nextPos = Movement.randomStep(pos, world.grid, world.config, rng);
        if (nextPos >= 0) {
            if (world.grid.tryMove(this, pos, nextPos)) pos = nextPos;
        }
    }

    @Override
    protected void decrementAlive() {
        world.stats.aliveAsexual.decrementAndGet();
    }

    private static void shuffleInts(int[] arr, ThreadLocalRandom rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }
}
