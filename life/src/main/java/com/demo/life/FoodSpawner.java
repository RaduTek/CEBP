package com.demo.life;

import java.util.concurrent.ThreadLocalRandom;

public final class FoodSpawner implements Runnable {
    private final World world;

    public FoodSpawner(World world) {
        this.world = world;
    }

    @Override
    public void run() {
        if (!world.running) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int total = world.config.width() * world.config.height();
        int placed = 0;
        int attempts = 0;
        while (placed < world.config.foodSpawnPerSecond() && attempts < world.config.foodSpawnPerSecond() * 5) {
            int pos = rng.nextInt(total);
            if (world.grid.tryPlace(pos, Food.UNIT)) placed++;
            attempts++;
        }
    }
}
