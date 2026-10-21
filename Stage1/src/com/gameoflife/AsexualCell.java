package com.gameoflife;

/** A cell whose reproduction creates two nearby asexual offspring. */
public class AsexualCell extends Cell {
    public AsexualCell(SimulationEngine engine) { super(CellType.ASEXUAL, engine); }
    @Override
    protected boolean reproduce() {
        if (state != CellState.REPRODUCING) state = CellState.REPRODUCING;
        WorldMap.BirthResult result = engine.getWorldMap().tryAsexualReproduce(
                this, () -> new AsexualCell(engine));
        if (result.success()) for (Cell child : result.offspring()) engine.startOffspring(child);
        return result.success();
    }
}
