package com.demo.life;

import java.util.concurrent.ThreadLocalRandom;

public final class Movement {
    private Movement() {}

    public static int stepToward(int from, int target, Grid grid, SimulationConfig config) {
        int fx = grid.xOf(from);
        int fy = grid.yOf(from);
        int tx = grid.xOf(target);
        int ty = grid.yOf(target);

        int[] neighbours = grid.neighbours(from);
        int bestPos = -1;
        int bestDist = Integer.MAX_VALUE;

        // Randomize iteration order for tie-breaking
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        shuffleInts(neighbours, rng);

        for (int n : neighbours) {
            if (grid.get(n) != null) continue; // tile occupied
            int nx = grid.xOf(n);
            int ny = grid.yOf(n);
            int dist = chebyshevOrManhattan(nx, ny, tx, ty, config.allowDiagonal());
            if (dist < bestDist) {
                bestDist = dist;
                bestPos = n;
            }
        }
        return bestPos; // -1 if no free neighbour toward target
    }

    public static int randomStep(int from, Grid grid, SimulationConfig config, ThreadLocalRandom rng) {
        int[] neighbours = grid.neighbours(from);
        shuffleInts(neighbours, rng);
        for (int n : neighbours) {
            if (grid.get(n) == null) return n;
        }
        return -1; // surrounded
    }

    private static int chebyshevOrManhattan(int x1, int y1, int x2, int y2, boolean diagonal) {
        int dx = Math.abs(x1 - x2);
        int dy = Math.abs(y1 - y2);
        return diagonal ? Math.max(dx, dy) : dx + dy;
    }

    private static void shuffleInts(int[] arr, ThreadLocalRandom rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }
}
