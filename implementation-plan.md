# Game of Life: Java Concurrency Demo, Implementation Plan

> Hand-off document for Claude Code. Work through the phases in order, run the tests of each phase before moving on, and commit after each phase. If a requirement is ambiguous, pick the documented default, make it configurable, and note it in the README.

## 1. Goal

A console simulation of a population of living cells on a 2D grid. Each cell is its own thread. The point of the project is to demonstrate **safe, lock-free concurrency**: many cells move, eat, die, and reproduce at the same time with no global lock.

### Rules of the simulation

- Cells live on a bounded `width x height` grid. **At most one entity (cell or food unit) may occupy a tile.**
- Food units are physical entities on tiles. They are a limited resource.
- Eating a food unit sustains a cell for `T_full`. After that the cell is **hungry**. If it does not eat within a further `T_starve`, it **dies**.
- On death the cell leaves a random number of food units, between 1 and 5.
- After eating at least `mealsToReproduce` (10) times, a cell must **multiply before it gets hungry again**.
- Two cell types:
  - **Asexual:** divides into two new hungry cells (the parent ceases to exist).
  - **Sexual:** multiplies only when it meets another cell that is also ready to reproduce. The result is one additional hungry cell. Both parents survive.
- Each cell is a separate thread. Threads are spawned through an `ExecutorService`.

## 2. Tech stack and constraints

- **Java 21+** (virtual threads), **Maven** (or Gradle if preferred), **JUnit 5**. No other runtime dependencies.
- Executor for cells: `Executors.newVirtualThreadPerTaskExecutor()`. Reason: cells are long-lived and mostly sleeping or blocked, so a fixed-size pool would starve once the population exceeds the pool size. Do NOT use a fixed thread pool for cells.
- Monitor/spawner use a separate single-thread `ScheduledExecutorService` (platform thread).
- Use `System.nanoTime()` for all deadlines, never wall-clock time.
- **Hard rule:** no `synchronized` blocks, `ReentrantLock`, or global lock around the grid. All grid coordination is CAS on individual tiles.

## 3. Assumptions and defaults (all configurable via `SimulationConfig`)

| Topic | Default |
|---|---|
| Grid | Bounded rectangle, 8-neighbour movement (the four orthogonal and the four diagonal neighbours). Config flag `allowDiagonal` (default `true`); set it to `false` for 4-neighbour movement. Optional `wrapAround` (torus). |
| Eating | A cell eats a food unit on an **adjacent** tile (any neighbour, including diagonals when `allowDiagonal` is on). The cell does not move onto it. The tile becomes empty. |
| Perception | Cell sees food/mates within `visionRadius` (Chebyshev distance, i.e. `max(|dx|, |dy|)`, which matches 8-neighbour movement; use Manhattan distance when `allowDiagonal` is `false`). Otherwise it random-walks. |
| Death drop | The cell's own tile becomes food. The remaining `n-1` units (n = random 1..5) go to the nearest free tiles via an expanding ring search. Units with no free tile are lost. |
| Food replenishment | A `FoodSpawner` drops `foodSpawnPerSecond` units on random free tiles. Default > 0, because the system is otherwise closed and goes extinct (each death returns about 3 units, but reproduction needs 10 meals). Setting it to 0 is allowed. |
| Asexual division | Needs one free tile adjacent to the parent. One child goes on that tile, the other takes the parent's tile. If no free tile is available, skip and retry in the next loop iteration. |
| Sexual mating | Needs two adjacent, ready sexual cells. The child is placed on a free tile adjacent to either parent. If none is free, mating is skipped and retried. Both parents reset their meal counters. |
| Meal counter | Counts meals since last reproduction. |
| Failed reproduction | A cell with >= 10 meals that finds no partner/space continues its normal cycle and stays eligible. |
| Newborns | Born hungry (start in the HUNGRY phase with a fresh `T_starve` deadline). |
| Population cap | None needed: the grid capacity is the natural cap. |

Suggested starting values (tune in Phase 8): grid `40x20`, `moveDelayMs=30`, `tFullMs=500`, `tStarveMs=1500`, `mealsToReproduce=10`, `visionRadius=6`, `initialFoodDensity=0.15`, `foodSpawnPerSecond=15`, `initialAsexual=20`, `initialSexual=20`, `runDurationMs=60000`.
Rule of thumb: `tStarveMs` must be well above `moveDelayMs x typical distance to food`, otherwise cells starve for purely geometric reasons.

## 4. Project structure

```
life/
├── pom.xml
├── README.md
└── src
    ├── main/java/com/demo/life/
    │   ├── Main.java                // parse args, build config, run simulation
    │   ├── SimulationConfig.java    // immutable record + defaults + validation
    │   ├── World.java               // Grid, executor, Stats, alive counter, running flag, spawn helper
    │   ├── Grid.java                // lock-free tile array (see section 5)
    │   ├── Entity.java              // sealed interface: Cell | Food
    │   ├── Food.java                // singleton UNIT
    │   ├── Cell.java                // abstract, implements Runnable and Entity
    │   ├── AsexualCell.java
    │   ├── SexualCell.java
    │   ├── CellState.java           // enum: HUNGRY, SATED, DEAD
    │   ├── Movement.java            // stepToward(), randomStep(), nearest(...)
    │   ├── FoodSpawner.java         // scheduled replenisher
    │   ├── GridRenderer.java        // ASCII/ANSI snapshot
    │   ├── Stats.java               // LongAdder counters
    │   └── Monitor.java             // scheduled stats + render
    └── test/java/com/demo/life/
        ├── GridConcurrencyTest.java
        ├── CellLifecycleTest.java
        ├── AsexualDivisionTest.java
        ├── MatingHandshakeTest.java
        └── SimulationSmokeTest.java
```

## 5. Core design: lock-free `Grid`

### 5.1 What CAS is and why we use it

CAS (compare-and-swap) atomically sets a slot to a new value only if it currently holds an expected value, and reports success. A thread that loses simply gets `false` and picks something else. Nobody blocks, so nothing can deadlock, and cells working in different areas of the map never interact.

### 5.2 API

```java
final class Grid {
    private final AtomicReferenceArray<Entity> tiles;   // index = y * width + x

    int width(); int height();
    Entity get(int pos);                        // volatile read; result may be stale
    boolean tryPlace(int pos, Entity e);        // CAS(null -> e)
    boolean tryConsumeFood(int pos);            // CAS(Food.UNIT -> null)
    boolean tryMove(Cell c, int dst);           // CAS(dst, null, c), then set(src, null)
    void clearOwn(int pos, Cell owner);         // owner only; set(pos, null)
    void setOwn(int pos, Entity e);             // owner only; used on death and division
    int[] neighbours(int pos);                  // 8 neighbours (4 if !allowDiagonal), respects wrapAround
    int nearestFree(int pos, int maxRadius);    // expanding ring search; -1 if none
    List<Integer> scan(int pos, int radius, Predicate<Entity> p);
}
```

### 5.3 Invariants and rules

1. **At most one entity per tile at all times.**
2. **Move protocol:** `CAS(dst, null, me)`, then `set(src, null)`, then `pos = dst`. For a brief instant the cell sits on two tiles. This is acceptable. **Never** clear the source before claiming the destination.
3. **Reads are snapshots.** A `scan()` result is never a reservation. Every decision is "look, then CAS, and handle failure by looking again."
4. **Tile claim doubles as slot reservation.** Always claim the tile (`tryPlace`) **before** submitting a new cell to the executor, so a child cannot run before its tile is set and the grid can never overflow.
5. A dying cell clears or replaces its own tile before its thread exits (no ghost occupants).
6. Add randomized tie-breaking among equidistant targets and a small random backoff after failed CAS attempts to reduce hotspots at food clusters.

## 6. Cell design

### 6.1 Fields

```java
abstract class Cell implements Entity, Runnable {
    final long id;                       // from World.idGenerator (AtomicLong)
    final World world;
    volatile int pos;                    // written only by the owning thread
    volatile CellState state;            // read by the monitor
    int mealsSinceReproduction;          // thread-confined; no synchronization needed
}
```

### 6.2 Lifecycle (`Cell.run()`, template method)

```
// Cell was placed on its tile by its creator, then submitted. It is born hungry.
while (world.running && !Thread.currentThread().isInterrupted()):
    // HUNGRY phase
    state = HUNGRY
    hungerDeadline = nanoTime + T_starve
    eaten = false
    while nanoTime < hungerDeadline:
        food = adjacent food tile?  -> if grid.tryConsumeFood(food): eaten = true; break
        else stepToward(nearest visible food) or randomStep(); sleep(moveDelay)
    if !eaten: die(); return
    meals++; stats.meals.increment()

    // SATED phase
    state = SATED
    satedDeadline = nanoTime + T_full
    while nanoTime < satedDeadline:
        if meals >= mealsToReproduce:
            if reproduce(satedDeadline): afterReproduction() ; (asexual: return)
        wanderOrSeekMate(); sleep(moveDelay)
```

- Handle `InterruptedException`: restore the interrupt flag and exit cleanly (clear own tile, decrement alive counter).
- Handle `RejectedExecutionException` when submitting children (executor shut down): roll back the child's tile and counters.
- `die()`: `n = ThreadLocalRandom.nextInt(1, 6)`; `grid.setOwn(pos, Food.UNIT)`; then place `n-1` more units with `nearestFree` + `tryPlace` (retry on CAS failure); decrement alive; record stats; `state = DEAD`.

### 6.3 Asexual division (`AsexualCell.reproduce`)

1. Find a free neighbour tile; `tryPlace(child2)` on it. On failure try another neighbour; if all fail, return false (retry later).
2. `grid.setOwn(pos, child1)` (the parent owns its tile, so a plain set is fine).
3. Increment alive count by 1 (net +1: parent replaced by two children), then `world.spawn(child1)` and `world.spawn(child2)`. On `RejectedExecutionException`, clear their tiles and fix counters.
4. The parent thread returns **without** calling `die()` (no food drop).

### 6.4 Sexual mating (`SexualCell`): the handshake

State per sexual cell:

```java
final AtomicLong claimedBy = new AtomicLong(0);   // 0 = free, else id of the claiming initiator
volatile boolean ready;                           // true during sated window when meals >= threshold
final AtomicBoolean matedFlag = new AtomicBoolean(false);  // set by initiator on the partner
```

**Why this design:** two cells may try to mate with each other at the same instant. Claims are non-blocking, so classic deadlock is impossible, but a naive "claim partner first, then self" scheme can livelock in perfect symmetry and cannot tell who owns a claim. The fix is **ordered claiming with owner recorded**, the standard lock-ordering rule applied to try-claims.

```java
boolean tryMate(SexualCell other) {
    SexualCell first  = this.id < other.id ? this  : other;
    SexualCell second = this.id < other.id ? other : this;

    if (!first.claimedBy.compareAndSet(0, this.id)) return false;     // lost; holds nothing
    try {
        if (!second.claimedBy.compareAndSet(0, this.id)) return false;
        try {
            // Re-check after claiming: earlier scan was only a snapshot.
            if (!first.ready || !second.ready || first.state != SATED || second.state != SATED) return false;
            int childPos = findFreeTileNear(first.pos, second.pos);   // tryPlace; -1 if none
            if (childPos < 0) return false;
            SexualCell child = new SexualCell(...);                    // tile already claimed
            alive.increment(); world.spawn(child);                     // place first, submit second
            first.matedFlag.set(true); second.matedFlag.set(true);     // each resets own meal counter
            return true;
        } finally { second.claimedBy.set(0); }
    } finally { first.claimedBy.set(0); }
}
```

Rules:

- **Both parents** reset `mealsSinceReproduction` to 0 on their own thread when they see `matedFlag` (the counter is thread-confined). The initiator may reset its own immediately.
- **Leaving the sated window safely:** before a ready cell transitions to HUNGRY (or dies/exits), it must first `CAS(claimedBy, 0 -> own id)`, set `ready = false`, then release. If the CAS fails, another cell is mid-mating with it: back off briefly and retry, then consume `matedFlag`. This guarantees a cell is never claimed while changing phase.
- **Only one child per pair**, because only the initiator creates it, and the loser of the first-slot CAS holds nothing.
- On failure, apply a randomized backoff (1-5 ms) before the next attempt, and choose among candidate partners with random tie-breaking.
- A ready cell scans for ready sexual cells within `visionRadius`, steps toward the nearest, and when adjacent calls `tryMate`.
- Stale position is acceptable: a partner may have moved by the time the child tile is chosen. The child is placed adjacent to the positions read at that moment, and that is fine.

## 7. Monitoring, spawner, shutdown

- **Monitor** (single-thread scheduled executor, every `statsIntervalMs`): prints alive (asexual/sexual), hungry, sated, food tiles on map, births, deaths, total meals, plus an ASCII grid render (`.` empty, `*` food, `a` asexual, `s` sexual; optional ANSI clear-and-redraw). The render reads tiles unsynchronized, which is fine for display only. Never use it for assertions.
- **FoodSpawner:** scheduled task placing food on random free tiles (`tryPlace`, retry a few times).
- **Shutdown:** after `runDurationMs` (or when alive == 0): set `running = false`, `executor.shutdownNow()`, `awaitTermination(5s)`, stop monitor/spawner, print final stats and a final audit result.

## 8. Concurrency checklist (review before every phase is considered done)

1. At most one entity per tile (audit in tests).
2. Every live cell appears in the grid once (twice only transiently mid-move) and `cell.pos` matches one of those tiles.
3. Never treat a scan as a reservation; only `tryPlace`, `tryConsumeFood`, `tryMove`, and `claimedBy` CAS results are truth.
4. Place on the grid first, then `submit` to the executor.
5. Claims are released in `finally`. Interrupt flag is restored.
6. `mealsSinceReproduction` is only ever touched by its owning thread; cross-thread signalling goes through `matedFlag`.
7. Counters use `LongAdder` / `AtomicInteger`; `state` and `pos` are `volatile`.
8. No locks around the grid; no blocking calls while holding a claim.

## 9. Build phases (with acceptance criteria)

### Phase 1: Skeleton
- Maven project, Java 21, JUnit 5, `SimulationConfig` record with validation, `World`, `Stats`, `Monitor`, `GridRenderer`, `Main` with CLI args (`--width`, `--height`, etc. or a properties file).
- **Done when:** running prints a random food map and zeroed stats, then exits cleanly.

### Phase 2: Grid correctness (no biology yet)
- Implement `Grid` and `GridConcurrencyTest`: ~1000 threads randomly place, move dummy entities, and consume food, while an auditor checks the one-entity-per-tile invariant every few ms.
- **Done when:** the stress test passes repeatedly (run it 20x), with no lost or duplicated entities.

### Phase 3: Single hungry cell
- `Cell`, `AsexualCell` (without reproduction), `Movement`, vision, food seeking, eating, starvation, death drop.
- **Done when:** a lone cell with reachable food survives; with food beyond reach it dies after `T_starve`; a death adds `min(n, reachable free tiles)` food tiles.

### Phase 4: Sated phase and meal counter
- Add `T_full`, the hungry/sated cycle, and the meal counter with reproduction disabled.
- **Done when:** timing tests (with tiny timings, e.g. `tFull=20ms`) confirm the cycle length and starvation boundary.

### Phase 5: Asexual division
- Free-tile claim, child placement, executor spawn, rollback on rejection, grid-full behaviour.
- **Done when:** the 10th meal leads to division (net +1 population); a full grid produces no division and no exception; population never exceeds tile count.

### Phase 6: Sexual mating
- `claimedBy` ordered handshake, `ready`/`matedFlag`, safe phase exit, child placement.
- **Done when:** `MatingHandshakeTest` passes (see section 10).

### Phase 7: Food spawner and mixed populations
- Add `FoodSpawner`, run both types competing for the same food.
- **Done when:** a 60 s run shows a visible boom/bust pattern without total extinction or a frozen simulation.

### Phase 8: Tuning
- Adjust grid size, food density, spawn rate, delays, `T_full`, `T_starve` until the demo is interesting for 30-60 s. Record the chosen defaults in the README.

### Phase 9: Shutdown and hardening
- Interrupt handling, rejected-execution handling, clean exit, final audit, thread-leak check after shutdown.

### Phase 10: Polish (optional)
- Live ANSI view, CSV time-series export, README with architecture diagram and a "concurrency talking points" section (CAS, ordered claiming, virtual threads, why no global lock).

## 10. Testing strategy

Make timing parameters injectable so tests run in milliseconds.

- **Grid stress:** many threads move/place/consume concurrently; audit invariant continuously.
- **Move safety:** a failed `tryMove` leaves the cell on its source tile.
- **Neighbourhood:** `neighbours()` returns 8 tiles for an interior position and 3 or 5 at corners/edges (4 interior, 2 or 3 at edges with `allowDiagonal=false`); with `wrapAround` it always returns 8 (or 4). Diagonal `tryMove` obeys the same one-entity-per-tile invariant, and `nearestFree` uses rings measured with the same distance metric as movement.
- **Starvation geometry:** food within reach: survives; food beyond reach: dies at about `T_starve`.
- **Death food conservation:** food tiles increase by `min(n, free reachable tiles)`; `n` is within 1..5 over many runs.
- **Asexual division:** exactly one net new cell; no division when no free neighbour; no exception when grid is full.
- **Mating handshake (critical):** place two ready sexual cells adjacent on a tiny grid; release both threads simultaneously via a `CountDownLatch`, each calling `tryMate`; repeat 100,000 times. Assert every round produces **exactly one child** (never zero, never two) and all `claimedBy` slots return to 0.
- **Crowded mating:** N ready cells packed together; births equal the number of successful claims; no cell participates in two simultaneous matings.
- **Smoke/soak:** 2-5 minute run with many cells: no exceptions, no deadlock (check with `jstack`), no leaked threads after shutdown, final audit passes, alive count equals the number of cell tiles on the grid.

## 11. Pitfalls to avoid

- Fixed-size thread pool for cells (freezes the simulation once the population exceeds it).
- Clearing the source tile before claiming the destination.
- Submitting a child to the executor before its tile is claimed.
- Treating a scan result as a reservation.
- Claiming mating partners in arbitrary order, or without recording the claim owner.
- Both parents creating a child (double births).
- Letting a ready cell change phase without first taking its own claim.
- Forgetting to restore the interrupt flag, or swallowing `InterruptedException`, which causes shutdown to hang.
- `T_starve` too short for the map size.
- Zero food replenishment in a long demo, so everything goes extinct.

## 12. Optional extensions

Walls/obstacles, food that regrows near existing food, per-cell mutation of `T_full`/vision/speed (natural selection), predator cells, a per-tile-lock or region-lock variant to benchmark against the lock-free design, and a seeded `RandomGenerator` for reproducible runs.

## 13. Definition of done

- All tests in section 10 pass, including repeated runs of the stress and handshake tests.
- `mvn test` is green and `mvn exec:java` (or the packaged jar) runs the demo for `runDurationMs` and exits cleanly with final stats.
- README documents the rules, config options, assumptions table, and how to run.
