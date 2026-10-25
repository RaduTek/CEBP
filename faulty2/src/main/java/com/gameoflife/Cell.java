package com.gameoflife;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** A cell with its own dedicated simulation thread. */
public abstract class Cell implements Runnable {
    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);
    protected static final int MEALS_TO_REPRODUCE = 10;

    protected final long id = ID_GENERATOR.getAndIncrement();
    protected final CellType type;
    protected final SimulationEngine engine;
    protected final ResourceManager resources;
    protected volatile CellState state = CellState.ALIVE;
    protected volatile int mealsEaten;
    private volatile Position position;
    private final boolean initiallyHungry;
    private volatile boolean seekingMate;
    private volatile Cell matePartner;
    private final AtomicBoolean deathHandled = new AtomicBoolean();

    protected Cell(CellType type, SimulationEngine engine, int x, int y) {
        this(type, engine, x, y, false);
    }

    protected Cell(CellType type, SimulationEngine engine, int x, int y, boolean initiallyHungry) {
        this.type = type;
        this.engine = engine;
        this.resources = engine.getResources();
        this.position = new Position(x, y);
        this.initiallyHungry = initiallyHungry;
    }

    @Override
    public void run() {
        boolean needsFoodImmediately = initiallyHungry;
        try {
            while (!engine.isShutdown() && !Thread.currentThread().isInterrupted()) {
                if (!needsFoodImmediately) {
                    state = CellState.ALIVE;
                    if (!sleep(engine.gettFullMs())) return;
                }
                needsFoodImmediately = false;

                state = CellState.HUNGRY;
                long deadline = System.nanoTime() + engine.gettStarveNanos();
                boolean fed = false;
                while (!engine.isShutdown() && !Thread.currentThread().isInterrupted()
                        && System.nanoTime() < deadline) {
                    moveTowardFood();
                    Position current = position;
                    if (engine.tryEatAt(current.x, current.y)) {
                        mealsEaten++;
                        engine.recordMeal();
                        fed = true;
                        break;
                    }
                    if (!sleep(Math.min(100, Math.max(1, engine.gettStarveMs() / 20)))) return;
                }
                if (engine.isShutdown() || Thread.currentThread().isInterrupted()) return;
                if (!fed) {
                    die();
                    return;
                }
                if (mealsEaten >= MEALS_TO_REPRODUCE) {
                    state = CellState.REPRODUCING;
                    if (reproduce()) {
                        mealsEaten = 0;
                        needsFoodImmediately = type == CellType.ASEXUAL;
                    }
                }
            }
        } finally {
            seekingMate = false;
            if (state == CellState.DEAD) engine.unregisterCell(this);
        }
    }

    protected abstract boolean reproduce();

    /** Called only while this cell and its nearby partner are both locked. */
    final boolean pairWith(Cell other) {
        if (other == this || !seekingMate || !other.seekingMate || state == CellState.DEAD
                || other.state == CellState.DEAD || matePartner != null || other.matePartner != null
                || type != CellType.SEXUAL || other.type != CellType.SEXUAL) return false;
        Position own = position, theirs = other.position;
        int dx = Math.abs(own.x - theirs.x), dy = Math.abs(own.y - theirs.y);
        if (Math.max(dx, dy) > 1) return false;
        matePartner = other;
        other.matePartner = this;
        seekingMate = false;
        other.seekingMate = false;
        return true;
    }

    protected boolean seekMate() {
        seekingMate = true;
        long deadline = System.nanoTime() + engine.getMatingTimeoutNanos();
        try {
            while (!engine.isShutdown() && !Thread.currentThread().isInterrupted()
                    && matePartner == null && System.nanoTime() < deadline) {
                moveRandomly();
                if (engine.tryPairNearby(this)) break;
                if (!sleep(75)) return false;
            }
            if (matePartner == null) return false;
            if (id < matePartner.id) {
                Position current = position;
                engine.submitCell(new SexualCell(engine, current.x, current.y, true));
            }
            return true;
        } finally {
            seekingMate = false;
            matePartner = null;
        }
    }

    protected void die() {
        if (!deathHandled.compareAndSet(false, true)) return;
        state = CellState.DEAD;
        Position current = position;
        engine.dropFoodAt(current.x, current.y, ThreadLocalRandom.current().nextInt(1, 6));
        engine.recordDeath();
    }

    protected void moveRandomly() {
        Position current = position;
        int nx = Math.max(0, Math.min(engine.getWidth() - 1, current.x + ThreadLocalRandom.current().nextInt(-1, 2)));
        int ny = Math.max(0, Math.min(engine.getHeight() - 1, current.y + ThreadLocalRandom.current().nextInt(-1, 2)));
        position = new Position(nx, ny);
    }

    private void moveTowardFood() {
        Position current = position;
        int[] target = resources.nearestFood(current.x, current.y);
        if (target == null) { moveRandomly(); return; }
        int dx = Integer.compare(target[0], current.x), dy = Integer.compare(target[1], current.y);
        int nx = Math.max(0, Math.min(engine.getWidth() - 1, current.x + dx));
        int ny = Math.max(0, Math.min(engine.getHeight() - 1, current.y + dy));
        position = new Position(nx, ny);
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public long getId() { return id; }
    public CellType getType() { return type; }
    public CellState getState() { return state; }
    public int getMealsEaten() { return mealsEaten; }
    public int getX() { return position.x; }
    public int getY() { return position.y; }
    boolean isSeekingMate() { return seekingMate; }

    @Override public String toString() { return type + "-cell#" + id + "[" + state + ", meals=" + mealsEaten + "]"; }

    private record Position(int x, int y) { }
}
