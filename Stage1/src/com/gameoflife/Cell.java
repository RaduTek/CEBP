package com.gameoflife;

import java.util.concurrent.ThreadLocalRandom;

/** A cell task that acts periodically in the shared world map. */
public abstract class Cell implements Runnable {
    private static final java.util.concurrent.atomic.AtomicLong ID_GENERATOR =
            new java.util.concurrent.atomic.AtomicLong(1);
    protected static final int MEALS_TO_REPRODUCE = 10;

    protected final long id = ID_GENERATOR.getAndIncrement();
    protected final CellType type;
    protected final SimulationEngine engine;
    protected volatile CellState state = CellState.HUNGRY;
    private volatile Position position;
    private volatile int mealsEaten;
    private volatile long starvationDeadline;

    protected Cell(CellType type, SimulationEngine engine) {
        this.type = type;
        this.engine = engine;
    }

    protected abstract boolean reproduce();

    @Override
    public final void run() {
        starvationDeadline = System.currentTimeMillis() + engine.gettStarveMs();
        try {
            while (!engine.isShutdown() && state != CellState.DEAD) {
                act();
                sleepQuietly(engine.nextMoveIntervalMs());
            }
        } catch (RuntimeException e) {
            System.err.println("Cell " + id + " failed: " + e.getMessage());
            engine.getWorldMap().tryKill(this, "unexpected cell failure");
        }
    }

    private void act() {
        if (engine.getWorldMap().tryStarve(this, System.currentTimeMillis()).status()
                == WorldMap.DeathStatus.KILLED) return;
        engine.getWorldMap().tryEatAtCurrentPosition(this);
        Position current = getPosition();
        if (current == null || state == CellState.DEAD) return;

        Position target = selectTarget(current);
        Position destination = target == null ? randomNeighbor(current) : stepToward(current, target);
        if (destination == null) return;
        WorldMap.MoveResult result = engine.getWorldMap().tryMoveAndEat(this, destination);
        if (result.status() == WorldMap.MoveStatus.MOVED_AND_ATE) {
            maybeReproduce();
        } else if (state == CellState.REPRODUCING) {
            tryReproduce();
        }
    }

    private Position selectTarget(Position current) {
        if (state == CellState.REPRODUCING && type == CellType.SEXUAL) {
            return engine.getWorldMap().findReproducingPartnersInView(this, engine.getFieldOfView())
                    .stream().findFirst().map(WorldMap.CellView::position).orElse(null);
        }
        return engine.getWorldMap().findFoodInView(this, engine.getFieldOfView())
                .stream().findFirst().map(WorldMap.FoodTarget::position).orElse(null);
    }

    private void maybeReproduce() {
        if (mealsEaten >= MEALS_TO_REPRODUCE) {
            state = CellState.REPRODUCING;
            tryReproduce();
        }
    }

    private void tryReproduce() {
        if (type == CellType.SEXUAL) {
            for (WorldMap.CellView view : engine.getWorldMap()
                    .findReproducingPartnersInView(this, engine.getFieldOfView())) {
                WorldMap.PairResult result = engine.getWorldMap().tryPairAndReproduce(
                        this, engine.findCell(view.id()), () -> new SexualCell(engine));
                if (result.success()) {
                    engine.startOffspring(result.offspring());
                    return;
                }
            }
        } else {
            WorldMap.BirthResult result = engine.getWorldMap().tryAsexualReproduce(
                    this, () -> new AsexualCell(engine));
            if (result.success()) {
                for (Cell offspring : result.offspring()) engine.startOffspring(offspring);
            }
        }
    }

    private Position randomNeighbor(Position current) {
        int dx = ThreadLocalRandom.current().nextInt(-1, 2);
        int dy = ThreadLocalRandom.current().nextInt(-1, 2);
        return dx == 0 && dy == 0 ? null : new Position(current.x() + dx, current.y() + dy);
    }

    private static Position stepToward(Position from, Position target) {
        return new Position(from.x() + Integer.signum(target.x() - from.x()),
                from.y() + Integer.signum(target.y() - from.y()));
    }

    protected void sleepQuietly(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void setPosition(Position position) { this.position = position; }
    void onMeal() {
        mealsEaten++;
        state = mealsEaten >= MEALS_TO_REPRODUCE ? CellState.REPRODUCING : CellState.ALIVE;
        starvationDeadline = System.currentTimeMillis() + engine.gettFullMs() + engine.gettStarveMs();
    }
    void markDead() { state = CellState.DEAD; }
    void resetAfterReproduction() {
        mealsEaten = 0;
        state = CellState.HUNGRY;
        starvationDeadline = System.currentTimeMillis() + engine.gettStarveMs();
    }
    long starvationDeadline() { return starvationDeadline; }

    public long getId() { return id; }
    public CellType getType() { return type; }
    public CellState getState() { return state; }
    public int getMealsEaten() { return mealsEaten; }
    public Position getPosition() { return engine.getWorldMap().getPosition(this); }

    @Override
    public String toString() {
        return type + "-cell#" + id + "[" + state + ", meals=" + mealsEaten + "]";
    }
}
