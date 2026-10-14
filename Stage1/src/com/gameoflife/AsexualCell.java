package com.gameoflife;

/**
 * A cell that reproduces through division: after ten meals it splits into
 * two new hungry asexual cells (plus itself, which resets its meal counter).
 */
public class AsexualCell extends Cell {

    public AsexualCell(SimulationEngine engine) {
        super(CellType.ASEXUAL, engine);
    }

    @Override
    protected boolean reproduce() {
        for (int i = 0; i < 2; i++) {
            engine.submitCell(new AsexualCell(engine));
        }
        return true;
    }
}