package com.gameoflife;

import java.util.ArrayList;
import java.util.List;

/** Matches eligible sexual cells. A cell can appear in the waiting list at most once. */
public final class MatingPool {
    private final List<SexualCell> waitingCells = new ArrayList<>();

    public synchronized SexualCell findMateOrWait(SexualCell cell) {
        for (int i = 0; i < waitingCells.size(); i++) {
            SexualCell candidate = waitingCells.get(i);
            if (candidate != cell && candidate.isAlive()) {
                waitingCells.remove(i);
                waitingCells.remove(cell);
                return candidate;
            }
        }

        if (!waitingCells.contains(cell) && cell.isAlive()) {
            waitingCells.add(cell);
        }
        return null;
    }

    public synchronized void remove(SexualCell cell) {
        waitingCells.remove(cell);
    }
}
