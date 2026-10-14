package com.gameoflife;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A living cell executed as a task by the simulation's thread pool.
 *
 * <p>Lifecycle: after birth the cell is fed for {@code tFullMs}; it then gets
 * hungry and must consume a food unit within {@code tStarveMs} or it dies,
 * releasing between one and five food units back into the pool. After ten
 * successful meals the cell reproduces according to its type.</p>
 */
public abstract class Cell implements Runnable {

    private static final java.util.concurrent.atomic.AtomicLong ID_GENERATOR =
            new java.util.concurrent.atomic.AtomicLong(1);

    protected final long id;
    protected final CellType type;
    protected final SimulationEngine engine;
    protected final ResourceManager resources;

    protected volatile CellState state = CellState.ALIVE;
    protected int mealsEaten;
    protected static final int MEALS_TO_REPRODUCE = 10;

    protected Cell(CellType type, SimulationEngine engine) {
        this.id = ID_GENERATOR.getAndIncrement();
        this.type = type;
        this.engine = engine;
        this.resources = engine.getResources();
        this.mealsEaten = 0;
    }

    @Override
    public void run() {
        try {
            while (!engine.isShutdown()) {
                // Fed state: the last meal sustains the cell for tFullMs.
                state = CellState.ALIVE;
                sleepQuietly(engine.gettFullMs());

                // Hungry state: try to secure a meal within tStarveMs.
                state = CellState.HUNGRY;
                boolean fed;
                try {
                    fed = resources.tryConsume(engine.gettStarveMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!fed) {
                    die();
                    return;
                }
                mealsEaten++;
                engine.recordMeal();

                if (mealsEaten >= MEALS_TO_REPRODUCE) {
                    state = CellState.REPRODUCING;
                    boolean reproduced = reproduce();
                    if (reproduced) {
                        mealsEaten = 0;
                    }
                    // On mating timeout the cell stays alive with its meal
                    // count and retries after the next meal.
                }
            }
        } finally {
            if (state == CellState.DEAD) {
                engine.unregisterCell(this);
            }
        }
    }

    /**
     * Type-specific reproduction; called after {@value #MEALS_TO_REPRODUCE} meals.
     *
     * @return {@code true} if reproduction completed (counter will be reset)
     */
    protected abstract boolean reproduce();

    /** Marks the cell as dead and releases 1-5 food units back into the pool. */
    protected void die() {
        state = CellState.DEAD;
        int released = ThreadLocalRandom.current().nextInt(1, 6);
        resources.releaseFood(released);
        engine.recordDeath();
    }

    protected void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long getId() {
        return id;
    }

    public CellType getType() {
        return type;
    }

    public CellState getState() {
        return state;
    }

    public int getMealsEaten() {
        return mealsEaten;
    }

    @Override
    public String toString() {
        return type + "-cell#" + id + "[" + state + ", meals=" + mealsEaten + "]";
    }
}