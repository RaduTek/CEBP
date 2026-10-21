package com.demo.life;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class Stats {
    public final LongAdder births = new LongAdder();
    public final LongAdder deaths = new LongAdder();
    public final LongAdder meals = new LongAdder();
    public final AtomicInteger aliveAsexual = new AtomicInteger(0);
    public final AtomicInteger aliveSexual = new AtomicInteger(0);

    public int totalAlive() {
        return aliveAsexual.get() + aliveSexual.get();
    }

    public void snapshot(StringBuilder sb) {
        sb.append("Alive: ").append(totalAlive())
          .append(" (A=").append(aliveAsexual.get())
          .append(", S=").append(aliveSexual.get()).append(")")
          .append("  Births=").append(births.sum())
          .append("  Deaths=").append(deaths.sum())
          .append("  Meals=").append(meals.sum());
    }
}
