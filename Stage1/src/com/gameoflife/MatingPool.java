package com.gameoflife;

import java.util.concurrent.TimeUnit;

/**
 * Thread-safe meeting point where sexual cells looking to reproduce pair up.
 *
 * <p>Protocol: a cell first tries {@link #arriveAndMatch(Cell)}; if a cell is
 * already waiting, the two are matched and the <em>arriving</em> cell creates
 * the offspring. Otherwise the cell registers itself with
 * {@link #tryRegisterAsWaiter(Cell)} and blocks in {@link #awaitPartner(Cell, long)}
 * until a partner arrives, the operation times out, or the pool is shut down.</p>
 */
public class MatingPool {

    private Cell waitingPartner;
    private Cell arrivedPartner;
    private boolean shutdown;

    /**
     * Attempts to match with a cell that is already waiting.
     *
     * @param self the arriving cell; handed to the waiting cell as its partner
     * @return the waiting partner, or {@code null} if nobody is waiting
     */
    public synchronized Cell arriveAndMatch(Cell self) {
        Cell partner = waitingPartner;
        if (partner != null) {
            waitingPartner = null;
            arrivedPartner = self;
            notifyAll();
        }
        return partner;
    }

    /**
     * Registers a cell as the waiting partner without blocking.
     *
     * @return {@code true} if registered, {@code false} if another cell is
     *         already waiting (the caller should then use
     *         {@link #arriveAndMatch(Cell)})
     */
    public synchronized boolean tryRegisterAsWaiter(Cell self) {
        if (shutdown || waitingPartner != null) {
            return false;
        }
        waitingPartner = self;
        return true;
    }

    /**
     * Blocks until another cell arrives to mate, or the operation times out
     * or the pool is shut down. Must be called after a successful
     * {@link #tryRegisterAsWaiter(Cell)}.
     *
     * @param self      the waiting cell
     * @param timeoutMs maximum time to wait for a partner
     * @return the partner cell that arrived, or {@code null} on timeout/shutdown
     */
    public synchronized Cell awaitPartner(Cell self, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (arrivedPartner == null && !shutdown) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
            }
            if (arrivedPartner == null || shutdown) {
                // Timed out or shut down: deregister so nobody matches a ghost.
                if (waitingPartner == self) {
                    waitingPartner = null;
                }
                return null;
            }
            return arrivedPartner;
        } finally {
            arrivedPartner = null;
        }
    }

    /** Wakes all waiting cells so they can exit during shutdown. */
    public synchronized void shutdown() {
        shutdown = true;
        waitingPartner = null;
        notifyAll();
    }
}