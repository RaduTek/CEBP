package com.gameoflife;

/**
 * A cell that reproduces sexually: after ten meals it looks for a partner
 * in the {@link MatingPool}. When two cells pair up, the arriving cell
 * creates one new hungry sexual cell; both parents reset their counters.
 */
public class SexualCell extends Cell {

    public SexualCell(SimulationEngine engine) {
        super(CellType.SEXUAL, engine);
    }

    @Override
    protected boolean reproduce() {
        MatingPool pool = engine.getMatingPool();
        long timeout = engine.getMatingTimeoutMs();

        // First try to match with a cell that is already waiting.
        Cell partner = pool.arriveAndMatch(this);
        if (partner != null) {
            // We arrived and found a waiting partner: we create the offspring.
            spawnOffspring();
            return true;
        }

        // Nobody waiting: offer ourselves and wait for an arrival.
        if (!pool.tryRegisterAsWaiter(this)) {
            // Another cell registered in the meantime: match with it.
            partner = pool.arriveAndMatch(this);
            if (partner != null) {
                spawnOffspring();
                return true;
            }
            return false;
        }
        try {
            partner = pool.awaitPartner(this, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (partner == null) {
            // Mating timed out or pool shut down: stay alive, retry later.
            return false;
        }
        // We waited; the arriving cell creates the offspring, we just reset.
        return true;
    }

    private void spawnOffspring() {
        engine.submitCell(new SexualCell(engine));
    }
}