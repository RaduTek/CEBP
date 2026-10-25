package com.gameoflife;

/** Thread-safe shared pool of food units. All access to food is protected by this object's monitor. */
public final class ResourceManager {
    private int currentFoodCount;

    public ResourceManager(int initialFood) {
        if (initialFood < 0) {
            throw new IllegalArgumentException("Initial food cannot be negative");
        }
        this.currentFoodCount = initialFood;
    }

    /**
     * Waits up to timeoutMillis for food, then consumes exactly one unit if available.
     * Returns false when the timeout expires with no food available.
     */
    public synchronized boolean consumeFood(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long remaining = timeoutMillis;

        while (currentFoodCount == 0 && remaining > 0) {
            wait(remaining);
            remaining = deadline - System.currentTimeMillis();
        }

        if (currentFoodCount == 0) {
            return false;
        }

        currentFoodCount--;
        return true;
    }

    public synchronized void releaseFood(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Food amount cannot be negative");
        }
        currentFoodCount += amount;
        notifyAll();
    }

    public synchronized int getCurrentFoodCount() {
        return currentFoodCount;
    }
}
