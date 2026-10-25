package com.gameoflife;

/**
 * A cell that reproduces through division: after ten meals it splits into
 * two new hungry asexual cells (plus itself, which resets its meal counter).
 */
public class AsexualCell extends Cell {

    public AsexualCell(SimulationEngine engine, int x, int y) {
        super(CellType.ASEXUAL, engine, x, y);
    }

    public AsexualCell(SimulationEngine engine, int x, int y, boolean initiallyHungry) {
        super(CellType.ASEXUAL, engine, x, y, initiallyHungry);
    }

    public AsexualCell(SimulationEngine engine) { this(engine, engine.randomX(), engine.randomY()); }

    @Override
    protected boolean reproduce() {
        engine.submitCell(new AsexualCell(engine, getX(), getY(), true));
        return true;
    }
}
