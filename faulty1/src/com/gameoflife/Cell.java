package com.gameoflife;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class Cell implements Runnable {
    private final int id;
    private final CellType type;
    protected final SimulationEngine simulation;
    private final AtomicInteger mealsEaten = new AtomicInteger();
    private volatile CellState state = CellState.HUNGRY;
    private volatile boolean alive = true;

    protected Cell(int id, CellType type, SimulationEngine simulation) {
        this.id = id;
        this.type = type;
        this.simulation = simulation;
    }

    public final int getId() { return id; }
    public final CellType getType() { return type; }
    public final boolean isAlive() { return alive; }
    public final CellState getState() { return state; }
    public final void resetMeals() { mealsEaten.set(0); }

    public final void stopCell() {
        alive = false;
        state = CellState.DEAD;
    }

    @Override
    public final void run() {
        try {
            while (alive && simulation.isRunning()) {
                state = CellState.HUNGRY;
                boolean ate = simulation.getResources().consumeFood(SimulationEngine.T_STARVE_MS);
                if (!ate) {
                    die();
                    break;
                }

                state = CellState.ALIVE;
                int meals = mealsEaten.incrementAndGet();
                System.out.printf("Cell %d ate (meal %d). Food remaining: %d%n",
                        id, meals, simulation.getResources().getCurrentFoodCount());

                if (meals >= 10) {
                    state = CellState.REPRODUCING;
                    boolean reproduced = reproduceIfReady();
                    if (reproduced) {
                        // Reproduction happens before the next hunger period. The cell continues hungry.
                        resetMeals();
                        state = CellState.HUNGRY;
                        continue;
                    }
                }
                Thread.sleep(SimulationEngine.T_FULL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Shutdown can interrupt a cell without it dying naturally; do not release food on shutdown.
            if (!alive) {
                state = CellState.DEAD;
            }
        }
    }

    protected abstract boolean reproduceIfReady();

    private void die() {
        if (alive) {
            alive = false;
            state = CellState.DEAD;
            simulation.cellDied(this);
        }
    }
}
