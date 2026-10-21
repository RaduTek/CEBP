package com.demo.life;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class SexualCell extends Cell {
    public final AtomicLong claimedBy = new AtomicLong(0); // 0 = free, else id of claiming initiator
    public volatile boolean ready = false;
    public final AtomicBoolean matedFlag = new AtomicBoolean(false);

    public SexualCell(long id, World world, int pos) {
        super(id, world, pos);
    }

    @Override
    public void run() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        try {
            while (world.running && !Thread.currentThread().isInterrupted()) {
                // HUNGRY phase
                state = CellState.HUNGRY;
                safeSetNotReady();
                long hungerDeadline = System.nanoTime() + world.config.tStarveMs() * 1_000_000L;
                boolean eaten = false;

                while (System.nanoTime() < hungerDeadline) {
                    if (!world.running || Thread.currentThread().isInterrupted()) return;

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

                    List<Integer> foodTiles = world.grid.scan(pos, world.config.visionRadius(), e -> e == Food.UNIT);
                    int nextPos;
                    if (!foodTiles.isEmpty()) {
                        int target = pickNearest(foodTiles, pos, rng);
                        nextPos = Movement.stepToward(pos, target, world.grid, world.config);
                    } else {
                        nextPos = Movement.randomStep(pos, world.grid, world.config, rng);
                    }

                    if (nextPos >= 0) {
                        if (world.grid.tryMove(this, pos, nextPos)) pos = nextPos;
                    }

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

                if (mealsSinceReproduction >= world.config.mealsToReproduce()) {
                    ready = true;
                }

                while (System.nanoTime() < satedDeadline) {
                    if (!world.running || Thread.currentThread().isInterrupted()) {
                        safeSetNotReady();
                        return;
                    }

                    if (mealsSinceReproduction >= world.config.mealsToReproduce()) {
                        ready = true;
                    }

                    // Check if we were mated by another cell
                    if (matedFlag.getAndSet(false)) {
                        mealsSinceReproduction = 0;
                        ready = false;
                    }

                    wanderOrSeekMate(rng);

                    try {
                        Thread.sleep(world.config.moveDelayMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        safeSetNotReady();
                        return;
                    }
                }

                safeSetNotReady();
            }
        } finally {
            safeSetNotReady();
            if (state != CellState.DEAD) {
                world.grid.clearOwn(pos, this);
                decrementAlive();
            }
        }
    }

    private void safeSetNotReady() {
        // Spin until we can safely mark not-ready (no one is mid-claiming us)
        while (true) {
            long current = claimedBy.get();
            if (current == 0) {
                ready = false;
                matedFlag.set(false);
                return;
            }
            // Someone is claiming us; backoff
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ready = false;
                return;
            }
        }
    }

    public boolean tryMate(SexualCell other) {
        SexualCell first  = this.id < other.id ? this  : other;
        SexualCell second = this.id < other.id ? other : this;

        if (!first.claimedBy.compareAndSet(0, this.id)) return false;
        try {
            if (!second.claimedBy.compareAndSet(0, this.id)) return false;
            try {
                // Re-verify after claiming (scan was a snapshot)
                if (!first.ready || !second.ready
                        || first.state != CellState.SATED || second.state != CellState.SATED) {
                    return false;
                }

                // Find and claim a free tile for the child
                SexualCell child = findAndPlaceChild(first.pos, second.pos);
                if (child == null) return false;

                world.stats.aliveSexual.incrementAndGet();
                world.stats.births.increment();

                try {
                    world.spawn(child);
                } catch (RejectedExecutionException e) {
                    world.grid.setOwn(child.pos, null);
                    world.stats.aliveSexual.decrementAndGet();
                    world.stats.births.add(-1);
                    return false;
                }

                // Signal both parents
                first.matedFlag.set(true);
                second.matedFlag.set(true);

                // Initiator resets immediately
                this.mealsSinceReproduction = 0;
                this.ready = false;

                return true;
            } finally {
                second.claimedBy.set(0);
            }
        } finally {
            first.claimedBy.set(0);
        }
    }

    private SexualCell findAndPlaceChild(int pos1, int pos2) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // Try neighbours of first parent
        int[] n1 = world.grid.neighbours(pos1);
        shuffleInts(n1, rng);
        for (int n : n1) {
            SexualCell child = new SexualCell(world.nextId(), world, n);
            if (world.grid.tryPlace(n, child)) return child;
        }
        // Try neighbours of second parent
        int[] n2 = world.grid.neighbours(pos2);
        shuffleInts(n2, rng);
        for (int n : n2) {
            SexualCell child = new SexualCell(world.nextId(), world, n);
            if (world.grid.tryPlace(n, child)) return child;
        }
        return null;
    }

    @Override
    protected boolean reproduce(long satedDeadline) {
        // Setting ready here; actual mating via wanderOrSeekMate -> tryMate
        ready = true;
        return false;
    }

    @Override
    protected void afterReproduction() {
        // Sexual cells continue living
    }

    @Override
    protected void wanderOrSeekMate(ThreadLocalRandom rng) {
        if (ready) {
            List<Integer> candidates = world.grid.scan(pos, world.config.visionRadius(),
                    e -> e instanceof SexualCell sc && sc.ready && sc != this);

            if (!candidates.isEmpty()) {
                int target = pickNearest(candidates, pos, rng);
                // Check if already adjacent
                for (int n : world.grid.neighbours(pos)) {
                    if (n == target) {
                        Entity e = world.grid.get(n);
                        if (e instanceof SexualCell sc && sc.ready && sc != this) {
                            if (tryMate(sc)) return;
                        }
                        break;
                    }
                }
                // Move toward mate
                int nextPos = Movement.stepToward(pos, target, world.grid, world.config);
                if (nextPos >= 0) {
                    if (world.grid.tryMove(this, pos, nextPos)) pos = nextPos;
                }
                return;
            }
        }

        int nextPos = Movement.randomStep(pos, world.grid, world.config, rng);
        if (nextPos >= 0) {
            if (world.grid.tryMove(this, pos, nextPos)) pos = nextPos;
        }
    }

    @Override
    protected void decrementAlive() {
        world.stats.aliveSexual.decrementAndGet();
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
