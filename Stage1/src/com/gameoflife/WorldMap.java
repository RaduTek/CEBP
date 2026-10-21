package com.gameoflife;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** The single source of truth for positions, food, population and counters. */
public final class WorldMap {
    public enum MoveStatus { MOVED, MOVED_AND_ATE, OUT_OF_BOUNDS, OCCUPIED, NOT_REGISTERED_OR_DEAD }
    public enum DeathStatus { KILLED, ALREADY_DEAD, NOT_REGISTERED, NOT_STARVED }
    public enum DeathReason { STARVATION, EXPLICIT, FAILURE, SHUTDOWN }

    public record MoveResult(MoveStatus status, Position position) {}
    public record DeathResult(DeathStatus status) {}
    public record FoodTarget(Position position) {}
    public record CellView(long id, Position position, CellType type, CellState state) {}
    public record WorldSnapshot(int population, int food, long births, long deaths, long meals,
                                int sexualPopulation, int asexualPopulation, int reproducingPopulation) {}
    public record RenderSnapshot(int width, int height, Set<Position> food,
                                 List<CellView> cells) {}
    public record PairResult(boolean success, Cell offspring) {}
    public record BirthResult(boolean success, List<Cell> offspring) {}

    private final int width;
    private final int height;
    private final int maxPopulation;
    private final int fieldOfView;
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<Position> foodPositions = new HashSet<>();
    private final Map<Long, Position> cellPositions = new HashMap<>();
    private final Map<Position, Cell> cellsByPosition = new HashMap<>();
    private final BooleanSupplier stopping;
    private long initialPopulation;
    private long births;
    private long deaths;
    private long meals;

    public WorldMap(int width, int height, int maxPopulation, BooleanSupplier stopping) {
        this(width, height, maxPopulation, Integer.MAX_VALUE, stopping);
    }

    public WorldMap(int width, int height, int maxPopulation, int fieldOfView,
                    BooleanSupplier stopping) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("map dimensions must be positive");
        }
        if (maxPopulation < 0 || maxPopulation > (long) width * height) {
            throw new IllegalArgumentException("maxPopulation must fit within the map");
        }
        this.width = width;
        this.height = height;
        this.maxPopulation = maxPopulation;
        this.fieldOfView = fieldOfView;
        this.stopping = stopping;
    }

    public WorldMap(int width, int height, int maxPopulation) {
        this(width, height, maxPopulation, Integer.MAX_VALUE, () -> false);
    }

    public boolean tryAddFood(Position position) {
        lock.lock();
        try { return inBounds(position) && foodPositions.add(position); }
        finally { lock.unlock(); }
    }

    public boolean tryRegister(Cell cell, Position position) {
        lock.lock();
        try {
            if (cell == null || position == null || !inBounds(position)
                    || cell.getState() == CellState.DEAD
                    || cellPositions.containsKey(cell.getId())
                    || cellPositions.size() >= maxPopulation
                    || cellsByPosition.containsKey(position)) {
                return false;
            }
            cellPositions.put(cell.getId(), position);
            cellsByPosition.put(position, cell);
            cell.setPosition(position);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public MoveResult tryMove(Cell cell, Position destination) {
        return move(cell, destination, false);
    }

    public MoveResult tryMoveAndEat(Cell cell, Position destination) {
        return move(cell, destination, true);
    }

    private MoveResult move(Cell cell, Position destination, boolean eat) {
        lock.lock();
        try {
            Position current = registeredPosition(cell);
            if (current == null) return new MoveResult(MoveStatus.NOT_REGISTERED_OR_DEAD, null);
            if (!inBounds(destination)) return new MoveResult(MoveStatus.OUT_OF_BOUNDS, current);
            if (!current.equals(destination) && cellsByPosition.containsKey(destination)) {
                return new MoveResult(MoveStatus.OCCUPIED, current);
            }
            if (!current.equals(destination)) {
                cellsByPosition.remove(current);
                cellsByPosition.put(destination, cell);
                cellPositions.put(cell.getId(), destination);
                cell.setPosition(destination);
            }
            boolean ate = eat && consumeFood(cell, destination);
            return new MoveResult(ate ? MoveStatus.MOVED_AND_ATE : MoveStatus.MOVED,
                    destination);
        } finally {
            lock.unlock();
        }
    }

    public boolean tryEatAtCurrentPosition(Cell cell) {
        lock.lock();
        try {
            Position position = registeredPosition(cell);
            return position != null && consumeFood(cell, position);
        } finally {
            lock.unlock();
        }
    }

    private boolean consumeFood(Cell cell, Position position) {
        if (!foodPositions.remove(position)) return false;
        meals++;
        cell.onMeal();
        return true;
    }

    public DeathResult tryKill(Cell cell, DeathReason reason) {
        return tryKill(cell, reason == null ? "unknown" : reason.name());
    }

    public DeathResult tryKill(Cell cell, String reason) {
        lock.lock();
        try {
            Position position = cellPositions.remove(cell.getId());
            if (position == null) {
                return cell.getState() == CellState.DEAD
                        ? new DeathResult(DeathStatus.ALREADY_DEAD)
                        : new DeathResult(DeathStatus.NOT_REGISTERED);
            }

            cellsByPosition.remove(position);
            cell.markDead();
            deaths++;
            spawnFoodLocked(ThreadLocalRandom.current().nextInt(1, 6));
            return new DeathResult(DeathStatus.KILLED);
        } finally {
            lock.unlock();
        }
    }

    public DeathResult tryStarve(Cell cell, long nowMs) {
        lock.lock();
        try {
            if (registeredPosition(cell) == null) {
                return cell.getState() == CellState.DEAD
                        ? new DeathResult(DeathStatus.ALREADY_DEAD)
                        : new DeathResult(DeathStatus.NOT_REGISTERED);
            }
            return cell.starvationDeadline() <= nowMs ? killLocked(cell) :
                    new DeathResult(DeathStatus.NOT_STARVED);
        } finally {
            lock.unlock();
        }
    }

    private DeathResult killLocked(Cell cell) {
        Position position = cellPositions.remove(cell.getId());
        if (position == null) return new DeathResult(DeathStatus.ALREADY_DEAD);
        cellsByPosition.remove(position);
        cell.markDead();
        deaths++;
        spawnFoodLocked(ThreadLocalRandom.current().nextInt(1, 6));
        return new DeathResult(DeathStatus.KILLED);
    }

    public List<FoodTarget> findFoodInView(Cell cell, int fov) {
        lock.lock();
        try {
            Position origin = registeredPosition(cell);
            if (origin == null) return List.of();
            List<FoodTarget> result = new ArrayList<>();
            for (int x = Math.max(0, origin.x() - fov); x <= Math.min(width - 1, origin.x() + fov); x++) {
                for (int y = Math.max(0, origin.y() - fov); y <= Math.min(height - 1, origin.y() + fov); y++) {
                    Position p = new Position(x, y);
                    if (!p.equals(origin) && foodPositions.contains(p)) result.add(new FoodTarget(p));
                }
            }
            result.sort(Comparator.comparingInt(t -> distance(origin, t.position())));
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    public List<CellView> findReproducingPartnersInView(Cell cell, int fov) {
        lock.lock();
        try {
            Position origin = registeredPosition(cell);
            if (origin == null) return List.of();
            List<CellView> result = new ArrayList<>();
            for (Map.Entry<Position, Cell> entry : cellsByPosition.entrySet()) {
                Cell other = entry.getValue();
                Position p = entry.getKey();
                if (other != cell && other.getType() == CellType.SEXUAL
                        && other.getState() == CellState.REPRODUCING
                        && inView(origin, p, fov)) {
                    result.add(new CellView(other.getId(), p, other.getType(), other.getState()));
                }
            }
            result.sort(Comparator.comparingInt(v -> distance(origin, v.position())));
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    public PairResult tryPairAndReproduce(Cell a, Cell b, Supplier<Cell> factory) {
        lock.lock();
        try {
            Position ap = registeredPosition(a);
            Position bp = registeredPosition(b);
            if (stopping.getAsBoolean() || a == b || ap == null || bp == null
                    || a.getType() != CellType.SEXUAL || b.getType() != CellType.SEXUAL
                    || a.getState() != CellState.REPRODUCING || b.getState() != CellState.REPRODUCING
                    || distance(ap, bp) > 1 || !inView(ap, bp, fieldOfView)
                    || cellPositions.size() >= maxPopulation) {
                return new PairResult(false, null);
            }
            Position childPosition = nearbyFreePosition(ap, bp);
            if (childPosition == null) return new PairResult(false, null);
            Cell child = factory.get();
            if (!tryRegisterLocked(child, childPosition)) return new PairResult(false, null);
            a.resetAfterReproduction();
            b.resetAfterReproduction();
            births++;
            return new PairResult(true, child);
        } finally {
            lock.unlock();
        }
    }

    public BirthResult tryAsexualReproduce(Cell parent, Supplier<Cell> factory) {
        lock.lock();
        try {
            Position parentPosition = registeredPosition(parent);
            if (stopping.getAsBoolean() || parentPosition == null
                    || parent.getState() != CellState.REPRODUCING
                    || cellPositions.size() + 2 > maxPopulation) {
                return new BirthResult(false, List.of());
            }
            List<Position> positions = nearbyFreePositions(parentPosition, 2);
            if (positions.size() < 2) return new BirthResult(false, List.of());
            Cell first = factory.get();
            Cell second = factory.get();
            if (!tryRegisterLocked(first, positions.get(0)) || !tryRegisterLocked(second, positions.get(1))) {
                if (cellPositions.remove(first.getId()) != null) cellsByPosition.remove(positions.get(0));
                return new BirthResult(false, List.of());
            }
            parent.resetAfterReproduction();
            births += 2;
            return new BirthResult(true, List.of(first, second));
        } finally {
            lock.unlock();
        }
    }

    public void rollbackBirth(Cell offspring) {
        lock.lock();
        try {
            Position position = cellPositions.remove(offspring.getId());
            if (position != null) {
                cellsByPosition.remove(position);
                births--;
            }
            offspring.markDead();
        } finally {
            lock.unlock();
        }
    }

    public int spawnFoodAtFreePositions(int count) {
        lock.lock();
        try {
            return spawnFoodLocked(count);
        } finally {
            lock.unlock();
        }
    }

    private int spawnFoodLocked(int count) {
        int added = 0;
        List<Position> candidates = allPositions();
        Collections.shuffle(candidates, ThreadLocalRandom.current());
        for (Position p : candidates) {
            if (added == count) break;
            if (foodPositions.add(p)) added++;
        }
        return added;
    }

    public Position getPosition(Cell cell) {
        lock.lock();
        try { return cellPositions.get(cell.getId()); }
        finally { lock.unlock(); }
    }

    public WorldSnapshot snapshot() {
        lock.lock();
        try {
            int sexual = 0, reproducing = 0;
            for (Cell cell : cellsByPosition.values()) {
                if (cell.getType() == CellType.SEXUAL) sexual++;
                if (cell.getState() == CellState.REPRODUCING) reproducing++;
            }

            return new WorldSnapshot(cellPositions.size(), foodPositions.size(), births, deaths, meals,
                    sexual, cellPositions.size() - sexual, reproducing);
        } finally {
            lock.unlock();
        }
    }

    /** Returns one immutable, spatially consistent frame for visualization. */
    public RenderSnapshot renderSnapshot() {
        lock.lock();
        try {
            List<CellView> cells = new ArrayList<>(cellsByPosition.size());
            for (Map.Entry<Position, Cell> entry : cellsByPosition.entrySet()) {
                Cell cell = entry.getValue();
                cells.add(new CellView(cell.getId(), entry.getKey(), cell.getType(), cell.getState()));
            }
            return new RenderSnapshot(width, height, Set.copyOf(foodPositions), List.copyOf(cells));
        } finally {
            lock.unlock();
        }
    }

    void setInitialPopulation() {
        lock.lock();
        try { initialPopulation = cellPositions.size(); }
        finally { lock.unlock(); }
    }

    void checkInvariants() {
        lock.lock();
        try {
            if (births == 0 && deaths == 0) initialPopulation = cellPositions.size();
            if (cellPositions.size() != cellsByPosition.size()
                    || cellPositions.size() != initialPopulation + births - deaths) {
                throw new IllegalStateException("world population invariant violated");
            }
            for (Map.Entry<Long, Position> entry : cellPositions.entrySet()) {
                if (cellsByPosition.get(entry.getValue()) == null
                        || cellsByPosition.get(entry.getValue()).getId() != entry.getKey()) {
                    throw new IllegalStateException("cell indexes are inconsistent");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private Position registeredPosition(Cell cell) {
        return cell == null || cell.getState() == CellState.DEAD ? null : cellPositions.get(cell.getId());
    }

    private boolean tryRegisterLocked(Cell cell, Position position) {
        if (cell == null || !inBounds(position) || cellPositions.size() >= maxPopulation
                || cellsByPosition.containsKey(position)) return false;
        cellPositions.put(cell.getId(), position);
        cellsByPosition.put(position, cell);
        cell.setPosition(position);
        return true;
    }

    private Position nearbyFreePosition(Position first, Position second) {
        List<Position> positions = nearbyFreePositions(first, 8);
        positions.addAll(nearbyFreePositions(second, 8));
        return positions.isEmpty() ? null : positions.get(0);
    }

    private List<Position> nearbyFreePositions(Position origin, int limit) {
        List<Position> result = new ArrayList<>();
        for (int dx = -1; dx <= 1 && result.size() < limit; dx++) {
            for (int dy = -1; dy <= 1 && result.size() < limit; dy++) {
                Position p = new Position(origin.x() + dx, origin.y() + dy);
                if (inBounds(p) && !cellsByPosition.containsKey(p) && !result.contains(p)) result.add(p);
            }
        }
        return result;
    }

    private List<Position> allPositions() {
        List<Position> result = new ArrayList<>(width * height);
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) result.add(new Position(x, y));
        return result;
    }

    private boolean inBounds(Position p) {
        return p != null && p.x() >= 0 && p.x() < width && p.y() >= 0 && p.y() < height;
    }

    private boolean inView(Position origin, Position target, int fov) {
        return Math.abs(target.x() - origin.x()) <= fov && Math.abs(target.y() - origin.y()) <= fov;
    }

    private static int distance(Position a, Position b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y()));
    }
}
