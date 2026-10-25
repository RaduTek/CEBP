package com.gameoflife;

/** Kept as a no-op stub; significant events are printed by EventLog directly from cell threads. */
public final class Monitor implements Runnable {
    public Monitor(World world, boolean ansiClear) {}
    public Monitor(World world, boolean ansiClear, boolean suppressGrid) {}

    @Override
    public void run() {}
}
