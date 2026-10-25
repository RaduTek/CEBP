package com.gameoflife;

final class EventLog {
    private EventLog() {}

    static void log(World world, String msg) {
        double t = (System.nanoTime() - world.startNs) / 1_000_000_000.0;
        System.out.printf("[%6.1fs] %s%n", t, msg);
    }
}
