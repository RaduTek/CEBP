package com.gameoflife;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe pool of food units shared by all cells.
 *
 * <p>Cells consume one unit per meal. When a cell dies it releases between
 * one and five units back into the pool. The pool can also be replenished
 * periodically by the simulation engine.</p>
 */
public class ResourceManager {

    private final int capacity;
    private int currentFoodCount;

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition notEmpty = lock.newCondition();

    /**
     * @param capacity      maximum number of food units the pool can hold
     * @param initialFood   food units available at simulation start
     */
    public ResourceManager(int capacity, int initialFood) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.currentFoodCount = Math.clamp(initialFood, 0, capacity);
    }

    /**
     * Attempts to consume one food unit, waiting up to {@code timeoutMs}
     * milliseconds for a unit to become available.
     *
     * @return {@code true} if a unit was consumed, {@code false} on timeout
     */
    public boolean tryConsume(long timeoutMs) throws InterruptedException {
        lock.lock();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (currentFoodCount <= 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                notEmpty.awaitNanos(remaining);
            }
            currentFoodCount--;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases food units back into the pool (e.g. when a cell dies) and
     * wakes up any cells waiting for food.
     *
     * @param amount number of units to add, capped at the pool capacity
     */
    public void releaseFood(int amount) {
        addFood(amount);
    }

    /**
     * Adds food units to the pool (periodic environmental generation or
     * cell death), waking waiting cells.
     *
     * @param amount number of units to add, capped at the pool capacity
     */
    public void addFood(int amount) {
        lock.lock();
        try {
            currentFoodCount = Math.min(currentFoodCount + Math.max(amount, 0), capacity);
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** @return the current number of available food units */
    public int getAvailableFood() {
        lock.lock();
        try {
            return currentFoodCount;
        } finally {
            lock.unlock();
        }
    }

    /** @return the maximum number of food units the pool can hold */
    public int getCapacity() {
        return capacity;
    }
}