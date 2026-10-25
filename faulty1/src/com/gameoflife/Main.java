package com.gameoflife;

public final class Main {
    private Main() { }

    public static void main(String[] args) throws InterruptedException {
        int initialFood = 50;
        int sexualCells = 5;
        int asexualCells = 0;
        long durationMs = 30_000;

        if (args.length >= 1) initialFood = Integer.parseInt(args[0]);
        if (args.length >= 2) sexualCells = Integer.parseInt(args[1]);
        if (args.length >= 3) asexualCells = Integer.parseInt(args[2]);
        if (args.length >= 4) durationMs = Long.parseLong(args[3]);

        SimulationEngine simulation = new SimulationEngine(initialFood);
        try {
            simulation.startInitialPopulation(sexualCells, asexualCells);
            Thread.sleep(durationMs);
        } finally {
            simulation.close();
        }
    }
}
