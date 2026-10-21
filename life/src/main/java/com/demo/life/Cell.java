package com.demo.life;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public abstract non-sealed class Cell implements Entity, Runnable {
    public final long id;
    public final World world;
    public volatile int pos;
    public volatile CellState state = CellState.HUNGRY;
    int mealsSinceReproduction = 0; // thread-confined

    protected Cell(long id, World world, int pos) {
        this.id = id;
        this.world = world;
        this.pos = pos;
    }

    @Override
    public void run() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        try {
            while (world.running && !Thread.currentThread().isInterrupted()) {
                // HUNGRY phase
                state = CellState.HUNGRY;
                long hungerDeadline = System.nanoTime() + world.config.tStarveMs() * 1_000_000L;
                boolean eaten = false;

                while (System.nanoTime() < hungerDeadline) {
                    if (!world.running || Thread.currentThread().isInterrupted()) return;

                    // Try to eat adjacent food
                    int[] neighbours = world.grid.neighbours(pos);
                    shuffleInts(neighbours, rng);
                    int foodPos = -1;
                    for (int n : neighbours) {
                        if (world.grid.get(n) == Food.UNIT) {
                            foodPos = n;
                            break;
                        }
                    }

                    if (foodPos >= 0 && world.grid.tryConsumeFood(foodPos)) {
                        eaten = true;
                        break;
                    }

                    // Move toward nearest visible food, or random walk
                    List<Integer> foodTiles = world.grid.scan(pos, world.config.visionRadius(), e -> e == Food.UNIT);
                    int nextPos;
                    if (!foodTiles.isEmpty()) {
                        int target = pickNearest(foodTiles, pos, rng);
                        nextPos = Movement.stepToward(pos, target, world.grid, world.config);
                    } else {
                        nextPos = Movement.randomStep(pos, world.grid, world.config, rng);
                    }

                    if (nextPos >= 0 && world.grid.tryMove(this, pos, nextPos)) pos = nextPos;

                    try {
                        Thread.sleep(world.config.moveDelayMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (!eaten) {
                    die();
                    return;
                }

                mealsSinceReproduction++;
                world.stats.meals.increment();

                // SATED phase
                state = CellState.SATED;
                long satedDeadline = System.nanoTime() + world.config.tFullMs() * 1_000_000L;

                while (System.nanoTime() < satedDeadline) {
                    if (!world.running || Thread.currentThread().isInterrupted()) return;

                    if (mealsSinceReproduction >= world.config.mealsToReproduce()) {
                        if (reproduce(satedDeadline)) {
                            afterReproduction();
                            return; // asexual exits; sexual cells never return true here
                        }
                    }

                    wanderOrSeekMate(rng);

                    try {
                        Thread.sleep(world.config.moveDelayMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            if (state != CellState.DEAD) {
                world.grid.clearOwn(pos, this);
                decrementAlive();
            }
        }
    }

    protected void afterReproduction() {
        // Default no-op; asexual cells set state=DEAD in reproduce() and run() returns
    }

    protected abstract boolean reproduce(long satedDeadline);

    protected abstract void wanderOrSeekMate(ThreadLocalRandom rng);

    protected abstract void decrementAlive();

    protected void die() {
        int n = ThreadLocalRandom.current().nextInt(1, 6);
        world.grid.setOwn(pos, Food.UNIT);
        for (int i = 1; i < n; i++) {
            int freePos = world.grid.nearestFree(pos, Math.max(world.config.width(), world.config.height()));
            if (freePos < 0) break;
            world.grid.tryPlace(freePos, Food.UNIT);
        }
        state = CellState.DEAD;
        world.stats.deaths.increment();
        decrementAlive();
    }

    protected int pickNearest(List<Integer> positions, int from, ThreadLocalRandom rng) {
        int bestDist = Integer.MAX_VALUE;
        int best = positions.get(0);
        int fx = world.grid.xOf(from);
        int fy = world.grid.yOf(from);
        // Shuffle first for tie-breaking randomness
        java.util.Collections.shuffle(positions, new java.util.Random(rng.nextLong()));
        for (int p : positions) {
            int px = world.grid.xOf(p);
            int py = world.grid.yOf(p);
            int dist = world.config.allowDiagonal()
                    ? Math.max(Math.abs(px - fx), Math.abs(py - fy))
                    : Math.abs(px - fx) + Math.abs(py - fy);
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
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
