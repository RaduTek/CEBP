package com.gameoflife;

public record SimulationConfig(
        int width,
        int height,
        long moveDelayMs,
        long tFullMs,
        long tStarveMs,
        int mealsToReproduce,
        int visionRadius,
        double initialFoodDensity,
        int foodSpawnPerSecond,
        int initialAsexual,
        int initialSexual,
        long runDurationMs,
        boolean allowDiagonal,
        boolean wrapAround,
        long statsIntervalMs
) {
    public SimulationConfig {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Grid dimensions must be positive");
        if (moveDelayMs <= 0) throw new IllegalArgumentException("moveDelayMs must be positive");
        if (tFullMs <= 0) throw new IllegalArgumentException("tFullMs must be positive");
        if (tStarveMs <= 0) throw new IllegalArgumentException("tStarveMs must be positive");
        if (mealsToReproduce <= 0) throw new IllegalArgumentException("mealsToReproduce must be positive");
        if (visionRadius < 0) throw new IllegalArgumentException("visionRadius must be non-negative");
        if (initialFoodDensity < 0 || initialFoodDensity > 1) throw new IllegalArgumentException("initialFoodDensity must be in [0,1]");
        if (foodSpawnPerSecond < 0) throw new IllegalArgumentException("foodSpawnPerSecond must be non-negative");
        if (initialAsexual < 0 || initialSexual < 0) throw new IllegalArgumentException("initial cell counts must be non-negative");
        if (runDurationMs <= 0) throw new IllegalArgumentException("runDurationMs must be positive");
        if (statsIntervalMs <= 0) throw new IllegalArgumentException("statsIntervalMs must be positive");
    }

    public static SimulationConfig defaults() {
        return new SimulationConfig(
                40, 20, 30, 500, 1500, 10, 6, 0.15, 15, 20, 20, 60000, true, false, 1000
        );
    }
}
