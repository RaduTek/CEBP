# Game of Life: Java Concurrency Demo

A console simulation of a population of living cells on a 2D grid, demonstrating **safe, lock-free concurrency**. Each cell is its own virtual thread. Many cells move, eat, die, and reproduce simultaneously with no global lock.

## Rules

- Cells live on a bounded `width x height` grid. At most one entity (cell or food) per tile.
- Food units are physical entities. Eating an adjacent food unit sustains a cell for `tFullMs`.
- After `tStarveMs` without eating, a cell dies, dropping 1–5 food units.
- After `mealsToReproduce` meals, a cell must reproduce before getting hungry again.
- **Asexual cells** divide into two new hungry cells (parent ceases to exist).
- **Sexual cells** reproduce only when meeting another ready sexual cell, producing one child. Both parents survive.

## Configuration Defaults

| Parameter | Default | Description |
|---|---|---|
| width | 40 | Grid width |
| height | 20 | Grid height |
| moveDelayMs | 30 | Sleep between moves (ms) |
| tFullMs | 500 | Time sated after eating (ms) |
| tStarveMs | 1500 | Time before starvation death (ms) |
| mealsToReproduce | 10 | Meals needed before reproduction |
| visionRadius | 6 | Chebyshev distance cells can see |
| initialFoodDensity | 0.15 | Fraction of tiles with food at start |
| foodSpawnPerSecond | 15 | New food units spawned per second |
| initialAsexual | 20 | Starting asexual cells |
| initialSexual | 20 | Starting sexual cells |
| runDurationMs | 60000 | Simulation duration (ms) |

## How to Run

```bash
# Default run (60 seconds)
mvn exec:java

# Custom run
mvn exec:java -Dexec.args="--width 60 --height 30 --run-duration 30000"

# Run tests
mvn test
```

## CLI Arguments

- `--width N` — grid width
- `--height N` — grid height
- `--move-delay N` — move delay in ms
- `--t-full N` — sated duration in ms
- `--t-starve N` — starvation deadline in ms
- `--meals-to-reproduce N` — meals before reproduction
- `--vision-radius N` — vision radius
- `--food-density D` — initial food density (0.0–1.0)
- `--food-spawn N` — food units spawned per second
- `--initial-asexual N` — initial asexual cell count
- `--initial-sexual N` — initial sexual cell count
- `--run-duration N` — run duration in ms
- `--stats-interval N` — stats print interval in ms

## Grid Display

```
. = empty tile
* = food unit
a = asexual cell
s = sexual cell
```

## Concurrency Design

### Lock-free Grid (CAS)

All grid coordination uses `AtomicReferenceArray<Entity>` with compare-and-swap (CAS). A thread that loses a CAS simply picks another target — no blocking, no deadlock.

**Move protocol:** `CAS(dst, null, me)` first, then `set(src, null)`. Never clear the source before claiming the destination.

### Virtual Threads

Each cell is a virtual thread via `Executors.newVirtualThreadPerTaskExecutor()`. Virtual threads are lightweight and suited for the many-sleeping-threads workload.

### Sexual Mating Handshake

Two sexual cells that both want to mate could try to claim each other simultaneously. The solution: always claim the cell with the **lower ID first**. This ordered claiming (standard lock-ordering) prevents livelock. Claims are stored in `AtomicLong claimedBy` and released in `finally` blocks.

### Why No Global Lock

A global lock would serialize all cell movement. With 40×20 = 800 cells, that's 800 threads waiting for one lock. CAS lets cells in different grid regions run truly in parallel — they only contend when targeting the same tile.
