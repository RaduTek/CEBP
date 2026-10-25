package com.gameoflife;

import java.util.concurrent.ThreadLocalRandom;

/** Food is stored on map tiles. Counters are intentionally unsynchronized. */
public class ResourceManager {
    private final int width, height, capacity;
    private final int[] food;
    private int total;

    /** Compatibility constructor for callers that only need a single pool. */
    public ResourceManager(int capacity, int initialFood) {
        this(1, 1, capacity, initialFood);
    }

    public ResourceManager(int width, int height, int capacity, int initialFood) {
        if (width <= 0 || height <= 0 || capacity <= 0) throw new IllegalArgumentException("dimensions and capacity must be positive");
        this.width = width;
        this.height = height;
        this.capacity = capacity;
        food = new int[width * height];
        int remaining = Math.min(Math.max(initialFood, 0), capacity);
        while (remaining-- > 0) {
            food[ThreadLocalRandom.current().nextInt(food.length)]++;
            total++;
        }
    }

    public boolean tryConsumeAt(int x, int y) {
        int index = index(x, y);
        if (food[index] <= 0) return false;
        food[index]--;
        total--;
        return true;
    }

    public void addFoodAt(int x, int y, int amount) {
        int index = index(x, y);
        int add = Math.max(0, amount);
        for (int i = 0; i < add; i++) {
            if (total >= capacity) return;
            total++;
            food[index]++;
        }
    }

    /** Distributes environmental food over random positions without a global lock. */
    public void addFood(int amount) {
        int n = Math.max(0, amount);
        for (int i = 0; i < n; i++) {
            int x = ThreadLocalRandom.current().nextInt(width), y = ThreadLocalRandom.current().nextInt(height);
            addFoodAt(x, y, 1);
        }
    }

    public void releaseFood(int amount) { addFood(amount); }

    public int getFoodAt(int x, int y) { return food[index(x, y)]; }

    /** Finds a currently stocked tile; the snapshot keeps this search lock-free. */
    public int[] nearestFood(int x, int y) {
        int[] snapshot = snapshot();
        int nearest = -1, distance = Integer.MAX_VALUE;
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] == 0) continue;
            int tx = i % width, ty = i / width;
            int d = Math.abs(tx - x) + Math.abs(ty - y);
            if (d < distance) { distance = d; nearest = i; }
        }
        return nearest < 0 ? null : new int[] { nearest % width, nearest / width };
    }

    public int[] snapshot() {
        int[] copy = new int[food.length];
        System.arraycopy(food, 0, copy, 0, food.length);
        return copy;
    }

    public int getAvailableFood() {
        return total;
    }

    public int getCapacity() { return capacity; }
    private int index(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) throw new IndexOutOfBoundsException();
        return y * width + x;
    }
}
