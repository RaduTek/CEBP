# Game of Life - Java Concurrency Showcase

## Team

- Contiu Cristina
- Marincu Bianca
- Mirzoca Radu
- Stoicovici Iasmina

## Assignment

Simulate a population of living cells, each with the goal of feeding and reproducing.

There is a limited number of food units (resources) that cells consume. A food unit sustains a cell for a given time T_full, after which the cell gets hungry. If it doesn't eat within another specified time T_starve, the cell dies, resulting in a random number of food units between 1 and 5.

After eating at least 10 times, a cell will multiply before getting hungry again. There are two types of cells: sexual and asexual. Asexual cells multiply through division, resulting in two hungry cells.

Sexual cells only multiply if they encounter another cell looking to reproduce, resulting in a third cell that was initially hungry.

In the simulation, cells will be represented by distinct threads of execution.
The Game of Life

Simulate a population of living cells, each with the goal of feeding and reproducing.

There is a limited number of food units (resources) that cells consume. A food unit sustains a cell for a given time T_full, after which the cell gets hungry. If it doesn't eat within another specified time T_starve, the cell dies, resulting in a random number of food units between 1 and 5.

After eating at least 10 times, a cell will multiply before getting hungry again. There are two types of cells: sexual and asexual. Asexual cells multiply through division, resulting in two hungry cells.

Sexual cells only multiply if they encounter another cell looking to reproduce, resulting in a third cell that was initially hungry.

In the simulation, cells will be represented by distinct threads of execution.


## AI usage

We used AI to research and understand the assignment task, understand the concurrency issues that can occur and write most of the code. It especially helped with quickly making a nice visualization of the maps.

Some have used AI in the web browser only, while others have played around with CLI and IDE integrated tools.


## Task distribution

We split work across team mates and syncronized up so that we're all on the same track, and so that each of us knows what the others are up to.

- Radu: The Good Implementation
- Iasmina: Faulty Implementation 1
- Cristina & Bianca: Faulty Implementation 2


## Faulty Implementation 1

This implementation is intentionally lacking a map. Food is not placed anywhere, it is a single global `int` inside `ResourceManager`, and every cell draws from that one shared counter. This has two consequences, one modelling and one concurrency.

**It does not model the problem:** With no grid there are no positions, no neighbours, no vision radius, and no movement. A cell does not find and eat *nearby* food; it simply blocks on the shared pool and consumes "a unit" from everywhere at once. Locality, which is the whole point of a spatial resource simulation, is gone.

**The counter forces a bad concurrency trade-off:** A plain shared integer cannot be decremented by many threads concurrently: a lock-free `check > 0 then decrement` would let the count race below zero. The only straightforward way to keep it correct is to wrap *all* access in one lock, which is exactly what this implementation does: `consumeFood`, `releaseFood`, and even `getCurrentFoodCount` are all `synchronized` on the single `ResourceManager` monitor (with `wait`/`notifyAll` for the empty-pool case).

So the implementation is thread-*safe*, but at the cost of being thread-*serial*: every eat, every food drop, and every read contends on one global lock. No matter how many cells run in parallel, the resource dimension of the simulation is fully serialized, defeating the whole point of using threading. The missing map is what leaves no option between "one global lock" and "a race that goes negative."

## Faulty Implementation 2

Concurrency issue: Data integrity

This implementation fixes faulty 1's missing map: food now lives per-tile in a `ResourceManager` backed by an `int[]` indexed by position, plus a running `total`, but it deliberately leaves that food **unsynchronized**, demonstrating the opposite failure from faulty 1.

**The core bug is a check-then-act race:** `tryConsumeAt` reads the tile (`food[index] <= 0`), then decrements both the tile and the total. These are three separate operations on plain, non-atomic fields with no lock between them. If a tile holds its last unit, two cell threads can both observe it as positive before either decrements so both "eat" the same unit. The result: tile counts and the global total can go **negative**, and `meals` can be incremented twice for one piece of food (double-counted reproduction progress). It is the textbook demonstration that an unlocked check-then-act sequence is not atomic.

**Food creation has the same defect:** `addFoodAt` checks the capacity limit and increments as separate steps, so the scheduled food generator and dying cells adding food concurrently can lose updates or push `total` past `capacity`. The per-tile amounts and the reported total can disagree.

**Reads are not snapshots:** The renderer and `nearestFood` copy the food array while workers keep mutating it, so the UI shows torn mixtures of old and new state. Notably, the implementation *does* protect some things correctly, namely the population registry is a `ConcurrentHashMap`, birth/death/meal totals are atomics, cell position/state fields are `volatile`, and sexual mating synchronizes on both partners in ascending ID order to avoid deadlock. The point is that these careful measures protect everything *except* food, which is left racy on purpose.

## The Good Implementation

The good implementation (in `life/`) keeps faulty 2's spatial map but makes every shared-state transition correct and lock-free, so it overcomes both failures at once.

**It has a real map (fixes faulty 1):** Food is a first-class `Entity` (`Food.UNIT`) that occupies a tile on a shared `Grid`. The grid is an `AtomicReferenceArray<Entity>`, so each tile holds either `null`, a food unit, or a cell. Cells have positions, see food within a `visionRadius`, move toward it, and eat only what is adjacent. The simulation actually models the spatial resource problem.

**Eating is a single atomic operation (fixes faulty 2's race):** There is no "check then act." Taking a food unit is one compare-and-swap:

```java
public boolean tryConsumeFood(int pos) {
    return tiles.compareAndSet(pos, Food.UNIT, null);  // claim-and-clear, indivisible
}
```

The check (tile currently holds `Food.UNIT`) and the act (replace it with `null`) are one indivisible step. Exactly one thread can win the CAS; any other cell racing for the same unit simply fails and looks elsewhere. The count can never go negative and a unit can never be eaten twice (the bug in faulty 2 is structurally impossible here).

**No global lock (fixes faulty 1's bottleneck):** All grid coordination is CAS on individual tiles; there is no shared monitor serializing access. Cells in different regions of the grid run truly in parallel and only contend when targeting the same tile, where a CAS loser just picks another target, so no blocking and no deadlock. This is the middle path faulty 1 lacked: correct *and* concurrent.

**Safe movement:** A cell moves by claiming the destination before releasing its source: `CAS(dst, null, me)` first, then `set(src, null)`. The source is never cleared before the destination is secured, so a cell is never lost and no tile ends up with two occupants. The one-entity-per-tile invariant holds by construction.

**Safe sexual mating:** Two sexual cells that each want to claim the other could deadlock or both grab the same partner. The handshake always claims the cell with the **lower ID first** (lock ordering), storing the claim in an `AtomicLong claimedBy` and releasing it in a `finally` block. Ordered claiming prevents the deadlock/livelock and guarantees a partner is claimed by only one suitor.

**Scalable threading:** Each cell is a virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`), which is cheap enough to run the many-sleeping-threads workload that a global-lock design like faulty 1 could never exploit.