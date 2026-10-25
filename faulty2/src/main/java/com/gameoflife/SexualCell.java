package com.gameoflife;

/**
 * A cell that reproduces sexually: after ten meals it looks for a partner
 * with another ready sexual cell on this map. A pair creates one offspring
 * near the parents; both parents reset their counters.
 */
public class SexualCell extends Cell {

    public SexualCell(SimulationEngine engine, int x, int y) { super(CellType.SEXUAL, engine, x, y); }
    public SexualCell(SimulationEngine engine, int x, int y, boolean initiallyHungry) { super(CellType.SEXUAL, engine, x, y, initiallyHungry); }
    public SexualCell(SimulationEngine engine) { this(engine, engine.randomX(), engine.randomY()); }

    @Override
    protected boolean reproduce() {
        return seekMate();
    }
}
