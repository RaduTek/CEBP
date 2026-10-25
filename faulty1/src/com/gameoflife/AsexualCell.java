package com.gameoflife;

public final class AsexualCell extends Cell {
    public AsexualCell(int id, SimulationEngine simulation) {
        super(id, CellType.ASEXUAL, simulation);
    }

    @Override
    protected boolean reproduceIfReady() {
        simulation.reproduceAsexually(this);
        return true;
    }
}
