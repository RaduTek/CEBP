package com.gameoflife;

public final class SexualCell extends Cell {
    public SexualCell(int id, SimulationEngine simulation) {
        super(id, CellType.SEXUAL, simulation);
    }

    @Override
    protected boolean reproduceIfReady() {
        SexualCell mate = simulation.getMatingPool().findMateOrWait(this);
        if (mate == null) {
            return false;
        }
        simulation.reproduceSexually(this, mate);
        return true;
    }
}
