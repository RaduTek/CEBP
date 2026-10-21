package com.gameoflife;

/** A cell that reproduces with a nearby reproducing sexual cell. */
public class SexualCell extends Cell {
    public SexualCell(SimulationEngine engine) { super(CellType.SEXUAL, engine); }
    @Override
    protected boolean reproduce() {
        if (state != CellState.REPRODUCING) state = CellState.REPRODUCING;
        for (WorldMap.CellView view : engine.getWorldMap()
                .findReproducingPartnersInView(this, engine.getFieldOfView())) {
            WorldMap.PairResult result = engine.getWorldMap().tryPairAndReproduce(
                    this, engine.findCell(view.id()), () -> new SexualCell(engine));
            if (result.success()) {
                engine.startOffspring(result.offspring());
                return true;
            }
        }
        return false;
    }
}
