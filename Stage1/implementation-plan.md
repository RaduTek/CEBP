# Implementation Plan: XY-Map Cell Simulation

This plan describes the reimplementation of the cell simulation around an
XY map containing cells and food. The current threaded execution model is
retained. Random behavior continues to use Java's `ThreadLocalRandom`; the
simulation does not attempt to produce identical results between runs.

Because every cell runs on its own thread and all of them share one world,
**concurrency correctness is a primary design goal, not an afterthought.**
Section 0 defines the rules that every later section depends on. Where a later
section says "atomic", it means "inside a single critical section of the
world map as defined in Section 0".

## 0. Concurrency principles (read first, apply everywhere)

These rules are mandatory. If an implementation step seems to conflict with
them, the rules win.

### 0.1 Single source of truth and single lock

- The `WorldMap` is the **only authority** on positions, food, occupancy, and
  population membership.
- `WorldMap` guards **all mutable world state** with **one**
  `ReentrantLock` (or `synchronized` on a private monitor). This covers the
  food set, both cell indexes, per-cell lifecycle transitions that involve the
  world, and the birth/death/meal counters.
- Do not use `ConcurrentHashMap`/concurrent sets as a substitute for the lock.
  Individually thread-safe collections do not make a compound update atomic.
  Plain `HashMap`/`HashSet` guarded by the lock are preferred, so there is one
  consistent mechanism.
- Fine-grained, striped, or per-cell locking is out of scope. If it is ever
  introduced, it requires a documented lock-ordering rule (acquire by ascending
  cell ID) and `tryLock` for non-blocking behavior.

### 0.2 Composite operations only

The map must **not** expose check-then-act primitives such as
`isOccupied(p)` followed by `move(p)`. Every state change is a single
composite method that validates and applies inside one critical section and
returns a result describing what happened (see Section 1.3 for the API).

### 0.3 Queries return immutable snapshots and are only hints

- Query methods (food in FOV, partners in FOV, statistics) return **immutable
  copies** (records or unmodifiable lists), never live collections or
  references to internal structures. This prevents
  `ConcurrentModificationException` and leaking unsynchronized state.
- Anything a query returns may be stale by the time the caller acts on it.
  The food may have been eaten, the partner may have moved, died, or been
  paired. **Every action re-validates inside its own atomic operation** and
  must treat failure as normal, non-exceptional, and retryable.

### 0.4 Never block, never sleep, never do I/O while holding the lock

- Critical sections must be short and bounded. No `Thread.sleep`, no waiting on
  conditions for other cells, no executor submission, no file/CSV I/O, no
  logging of large objects inside the lock.
- Sleeping between actions happens **outside** the lock only.
- Executor submission (starting offspring tasks) happens **after** the lock is
  released (see Sections 8 and 9).
- Scans inside the lock must be bounded (FOV window, 8-neighborhood, or a
  bounded free-position search), never an unbounded retry loop.

### 0.5 Ownership and visibility of `Cell` state

- Position is stored **only in the map**. `Cell.getPosition()` either
  delegates to the map or reads a `volatile` field that is **written only
  inside the map's critical section**. A cell never assigns its own position.
- Lifecycle `state` is `volatile` (or an `AtomicReference<CellState>`).
  Transitions that involve other cells or the map (death, pairing, eating,
  reproduction reset) happen inside the map's critical section.
- Fields that another thread can read or write (`state`, meal counter,
  fullness timer, starvation deadline) are either `volatile`/atomic or
  guarded by the map lock. The pairing operation resets *another* thread's
  cell, so these fields must not be plain unsynchronized fields.
- `DEAD` is terminal. Transitions out of `DEAD` are forbidden and rejected.

### 0.6 Idempotent, validated lifecycle

- Death is idempotent: the first caller to transition a registered cell to
  `DEAD` wins; later callers get "already dead" and change nothing (no double
  removal, no double death count, no double food spawn).
- Every map operation taking a `Cell` first verifies that the cell is
  registered and not `DEAD`. Operations from dead or unregistered cells are
  rejected and return a failure result. A cell thread seeing a failed result
  checks its own liveness and exits its loop if dead.

### 0.7 Counters and statistics consistency

- `births`, `deaths`, `meals` are updated **inside** the same critical section
  as the change they describe, so a snapshot taken under the lock is
  internally consistent.
- Invariant, always true under the lock:
  `population == initialPopulation + births - deaths`, and
  `population == cellPositions.size() == cellsByPosition.size()`.

### 0.8 Randomness

- Call `ThreadLocalRandom.current()` at each use site. Never store it in a
  field, pass it to another thread, or capture it in a lambda that may run on
  a different thread.

---

## 1. Spatial model

Add a coordinate value type:

```java
public record Position(int x, int y) {}
```

Add a thread-safe world/map component, `WorldMap`, responsible for:

- Map width and height.
- Food positions.
- Cell positions.
- Registering and removing cells.
- Moving cells within map boundaries.
- Finding food in a cell's field of view.
- Finding compatible cells in a cell's field of view.
- Consuming food at a cell's current position.
- Pairing, offspring placement, death handling, and statistics snapshots.

Recommended initial representation (all guarded by the single map lock,
Section 0.1):

```text
Set<Position> foodPositions
Map<Long, Position> cellPositions
Map<Position, Cell> cellsByPosition
long births, deaths, meals          // guarded by the same lock
```

The map should define the collision policy explicitly. The recommended
behavior is:

- One cell per position.
- Food may share a position with a cell.
- A move outside the map is rejected.
- A move to an occupied position is rejected.
- Food is consumed when a cell successfully enters or occupies its position.

### 1.1 Invariants

The following must hold whenever the lock is not held by a mutating thread
(i.e. after every completed operation):

1. `cellPositions` and `cellsByPosition` are exact inverses of each other.
2. No position holds more than one cell.
3. Every registered cell is inside map bounds and is not `DEAD`.
4. Every food position is inside map bounds. There are no duplicates (it is a
   set).
5. `births`, `deaths`, and population satisfy the formula in Section 0.7.

Provide a package-private `checkInvariants()` method (acquires the lock,
throws `IllegalStateException` describing the violation) for use in tests.

### 1.2 Atomicity

All compound operations that update both cell indexes, the food set, cell
state, or counters must be atomic. A move is a **single critical section**
that removes the old index entries and inserts the new ones; no other thread
may observe the cell at neither or both positions.

### 1.3 Required composite operations (the map's public mutation API)

Names are recommendations. The contract is what matters: each is one critical
section, re-validates all preconditions inside it, never blocks, and returns a
result object rather than throwing on ordinary contention failures.

```java
// Registration (used during initialization, and for offspring)
boolean tryRegister(Cell cell, Position p);          // free, in bounds, not already registered

// Movement and feeding
MoveResult tryMove(Cell cell, Position dest);        // in bounds, free, cell alive & registered
MoveResult tryMoveAndEat(Cell cell, Position dest);  // tryMove + consume food at dest + meal++ + state/timers
boolean    tryEatAtCurrentPosition(Cell cell);       // food spawned under a stationary cell

// Death
DeathResult tryKill(Cell cell, DeathReason reason);  // idempotent; removes cell, deaths++, optional food spawn
DeathResult tryStarve(Cell cell, long nowMs);        // re-checks starvation deadline inside the lock, then kills

// Reproduction (Sections 8 and 9)
PairResult  tryPairAndReproduce(Cell a, Cell b, Supplier<Cell> offspringFactory);
BirthResult tryAsexualReproduce(Cell parent, Supplier<Cell> offspringFactory);
void        rollbackBirth(Cell offspring);           // undo registration if its task cannot be started

// Food
int spawnFoodAtFreePositions(int count);             // bounded search; may place fewer than requested

// Queries (immutable snapshots, hints only)
List<FoodTarget> findFoodInView(Cell cell, int fov);
List<CellView>   findReproducingPartnersInView(Cell cell, int fov);
WorldSnapshot    snapshot();                         // population, food, births, deaths, meals, per-type counts
```

Notes:

- `MoveResult` should distinguish at least `MOVED`, `MOVED_AND_ATE`,
  `OUT_OF_BOUNDS`, `OCCUPIED`, and `NOT_REGISTERED_OR_DEAD`. Callers treat all
  non-moved results as "stay put and retry next interval".
- `CellView` is an immutable record (`id`, `position`, `type`, `state`). Do
  not return live `Cell` references from queries.
- Offspring are produced through a `Supplier<Cell>`/factory so the map can
  assign the cell and its position in the same critical section. Construction
  must be cheap and must not start threads, submit tasks, or block.

---

## 2. Simulation configuration

Extend `SimulationEngine.Config` with spatial settings:

```java
int mapWidth;
int mapHeight;
int initialFoodCount;
int fieldOfView;
long minMoveIntervalMs;
long maxMoveIntervalMs;
int maxPopulation;
```

Do not add a random seed parameter. Random choices should continue using
`ThreadLocalRandom.current()`.

Validate:

- Map dimensions are positive.
- `initialFoodCount` is non-negative and does not exceed map capacity.
- `fieldOfView` is non-negative.
- `minMoveIntervalMs` is non-negative.
- `maxMoveIntervalMs >= minMoveIntervalMs`.
- `maxPopulation` is at least the initial population and does not exceed map
  capacity (one cell per position).
- The executor/thread pool can run `maxPopulation` cell tasks concurrently.
  Because every cell is a long-lived task that sleeps between actions, a pool
  smaller than the population would leave cells unscheduled (starving without
  ever acting). Either size the pool to `maxPopulation` or document and
  enforce a different explicit strategy.

`maxPopulation` is the explicit bound on thread growth. When it is reached,
reproduction is **deferred** (parents stay `REPRODUCING` and retry later), not
failed with an exception, and no unbounded thread creation occurs.

Keep the existing lifecycle, starvation, reproduction, statistics, and
population settings unless they are changed as a consequence of introducing
the map.

Configuration is immutable after construction (final fields or a record), so
it can be shared safely across threads without synchronization.

---

## 3. Initializing the map and food

Add an explicit initialization phase before any cell tasks are submitted. The
initialization phase is **single-threaded**; it completes and then publishes
the map and cells to the worker threads through the executor submission (which
provides the required happens-before edge). No state may be changed outside
the map after tasks begin.

1. Construct the XY map.
2. Select `initialFoodCount` random, unique positions.
3. Add food to those positions.
4. Create the initial sexual and asexual cells with `CellState.HUNGRY`.
5. Assign each cell a valid random starting position.
6. Register each cell in the map and engine registry.
7. Start the cell tasks and schedulers.

Food must be present before cells start executing.

Position selection must **not** use "repeat random until free" because it can
spin for a very long time (or forever) when the map is nearly full. Instead,
build the list of all candidate positions once, shuffle it using
`ThreadLocalRandom` (or a partial Fisher-Yates), and take the first
`initialFoodCount` positions for food. Take initial cell positions from
the same shuffled list (cells may overlap food positions, but not each other),
or from a second shuffle. This is bounded and cannot loop indefinitely.

If the requested population exceeds available map positions, fail
configuration validation with a clear `IllegalArgumentException` (before any
thread is started).

Cell timers (fullness/starvation) must be initialized at the moment the cell's
task actually starts (or be defined relative to creation consistently), so a
cell is not penalized for time spent waiting to be scheduled.

---

## 4. Field of view

Use a documented square field of view centered on the cell:

```text
abs(target.x - origin.x) <= fieldOfView
and
abs(target.y - origin.y) <= fieldOfView
```

Exclude the cell's own position from search results.

The map should return targets in a stable local order where practical, such as
by distance and then coordinates. This ordering is useful for predictable
behavior within one execution, but it is not intended to guarantee identical
simulation runs.

The field of view should be used for both:

- Finding food for normal cells.
- Finding reproducing sexual cells for sexual reproduction.

Concurrency requirements:

- FOV queries run inside one critical section, copy the matching results into
  an immutable list, and return after releasing the lock. Sorting and any
  further processing of the result happen **outside** the lock when possible.
- Scan cost is bounded by `(2 * fov + 1)^2` positions (iterate the window and
  look each position up), **not** by iterating all food or all cells, unless
  the total is smaller. Choose whichever is cheaper, but keep it bounded so the
  lock hold time stays short.
- Results are hints (Section 0.3). A cell must expect that its chosen target is
  gone when it acts.

---

## 5. Cell positions and non-blocking movement

Update `Cell` to expose its current `Position` as defined in Section 0.5 (map
is authoritative; the cell does not write it itself). Every cell should start
in the `HUNGRY` state, including the initial population and all offspring.

Cells must not block while waiting for a specific lifecycle stage, food,
partner, or reproduction opportunity. A cell should remain an active task that
periodically performs one short action and then yields until its next action.
The action loop should be interruptible during shutdown.

At each random movement/action interval:

1. Check whether the cell is still alive and the engine is still running; exit
   the loop if not.
2. Check starvation via `tryStarve(cell, now)` (Section 6).
3. Check for food at the current position via `tryEatAtCurrentPosition(cell)`
   (covers food spawned under a stationary or just-moved cell).
4. Determine its current target-selection mode.
5. Search the map (snapshot query) for the appropriate target in its field of
   view.
6. If a target exists, compute one step toward it.
7. Otherwise, select a random neighboring position.
8. Ask the map to perform the move (and consume food at the destination, if
   any) as **one atomic operation** (`tryMoveAndEat`).
9. Update timers/state **only as a consequence of the operation's result**.
10. Release control (no locks held), sleep for the next interval, and repeat.

The interval sleep happens strictly outside any lock. A `InterruptedException`
must restore the interrupt flag (`Thread.currentThread().interrupt()`) and
exit the loop cleanly. It must not be swallowed.

Any unexpected `RuntimeException` inside one action must be caught at the loop
boundary, logged, and the cell then follows a defined policy (e.g. terminate
this cell via `tryKill`) so one failing cell cannot leave a ghost on the map
with no running task.

Movement should use the eight neighboring positions, excluding the current
position. Invalid and occupied destinations should be ignored. If no valid
destination exists, the cell remains in place and tries again later. A failed
move is a normal outcome: it requires no rollback and no exception, because the
map operation is atomic and had no effect.

For a visible target, calculate the step independently on each axis:

```text
stepX = sign(target.x - current.x)
stepY = sign(target.y - current.y)
```

Use the cell's position as of the time the snapshot was taken; the map
re-validates the destination, so a stale position cannot corrupt state. If the
move is rejected, the cell may try the random-neighbor fallback on the next
interval.

If more than one target is visible, select one according to the map's
ordering or choose randomly with `ThreadLocalRandom`.

The movement interval should be selected from the configured range. If
`minMoveIntervalMs == maxMoveIntervalMs`, use that fixed interval. A random
component in the interval is also important for liveness: it prevents two
cells from repeatedly colliding in lock-step.

---

## 6. Feeding and starvation

Retain the existing state values from `Cell.java`, but treat them as
observable status rather than blocking execution stages:

- `HUNGRY`: the cell has not eaten recently or was just created.
- `ALIVE`: the cell has recently eaten and remains eligible for normal actions.
- `REPRODUCING`: the cell has reached the reproduction threshold and is
  actively searching for a partner.
- `DEAD`: the cell has been removed from the map.

`HUNGRY` must not mean that the cell waits inside a food-pool operation. A
hungry cell continues moving and checking its current position on every action
interval. Its starvation deadline is checked against elapsed time while the
cell continues to act.

Replace global food acquisition through `ResourceManager.tryConsume(...)`
with map-based food consumption. Consumption is part of the **same atomic
operation as the move** (or `tryEatAtCurrentPosition` for a stationary cell),
not a separate call using a possibly stale `cell.getPosition()`:

```java
worldMap.tryMoveAndEat(cell, destination)
worldMap.tryEatAtCurrentPosition(cell)
```

Inside the critical section, "eat" means: remove the food position from the
set; if (and only if) it was present, increment the meal counter, reset the
fullness timer, clear the starvation deadline, and set the state to `ALIVE`
unless reproduction is triggered. Two cells can never consume the same food
item because only the operation that actually removes it proceeds. Meal
counters and timers are never updated after a failed consumption.

The cell becomes `HUNGRY` after `tFullMs`. It must reach food and consume it
within `tStarveMs`, otherwise it dies. Consuming food immediately resets the
fullness timer, clears the starvation deadline, increments the meal count, and
returns the cell to `ALIVE` unless reproduction is triggered.

**Starvation is checked via the map, not by the cell alone.** The cell's own
thread calls `tryStarve(cell, now)`, which **re-reads the deadline inside the
critical section** and kills the cell only if the deadline has still passed.
This prevents the race where the cell eats (or is reset by a pairing) between
the thread's unsynchronized deadline check and the actual death.

When a cell dies (single atomic `tryKill`/`tryStarve` operation):

1. Verify the cell is registered and not already `DEAD` (otherwise return
   "already dead" and do nothing).
2. Mark it as `DEAD`.
3. Remove it from the world map (both indexes).
4. Remove it from the engine registry.
5. Increment the death count.
6. Optionally place one to five new food items at random free positions if
   the original death-food rule is retained.

If death releases food, use `ThreadLocalRandom.current().nextInt(1, 6)` and
make the map insertion explicit and part of the **same critical section**.
Free-position search must be bounded (e.g. a fixed number of random attempts,
then a bounded scan) and placing fewer than requested, or none on a full map,
is acceptable and must not error or loop. Do not use the old global resource
count as the source of truth.

The engine registry must be updated consistently with the map. Preferred: the
map's `cellPositions` **is** the registry for population purposes, so there is
no second collection to keep in sync. If a separate engine-level registry
(e.g. for task handles) must remain, update it in the same critical section
(or ensure it is only a derived view that is never used for population counts).

---

## 7. Normal and sexual target selection

Normal cells search for food:

```text
target = nearest or selected food position in the field of view
```

Sexual cells search for food until they reach the reproduction threshold.
After reaching that threshold:

1. Set the state to `REPRODUCING` (as part of the atomic eat operation that
   reached the threshold, so the transition is visible consistently).
2. On each action interval, search for another reproducing sexual cell in the
   field of view.
3. Move toward that cell instead of prioritizing food.
4. Attempt an atomic pairing when the cells are close enough.
5. If no partner is visible or pairing fails, continue moving and retry on a
   later interval.

Asexual cells never search for mating partners.

Target selection should be represented by overridable behavior in the cell
classes or by a small shared target-selection helper. Avoid duplicating map
query and movement code in `SexualCell` and `AsexualCell`.

Concurrency notes:

- The partner search returns `CellView` snapshots only. It confers no claim on
  the partner. Whether the partner is still available is decided solely
  inside `tryPairAndReproduce`.
- A `REPRODUCING` cell that is also adjacent to food does not need special
  handling; it simply does not prioritize food.
- Target selection code must not hold or assume any lock; it works on the
  returned snapshot.

---

## 8. Spatial sexual reproduction

Replace the current global waiting behavior in `MatingPool` with spatial
matching.

Pairing is implemented as a **single atomic map operation**,
`tryPairAndReproduce(a, b, offspringFactory)`. Because all map state changes
occur under the one map lock, the "claim" and the "birth" happen in the same
critical section; no separate claim state or cross-cell locking is needed, and
there is therefore no lock-ordering deadlock risk.

Inside the critical section, the operation must verify **all** of the
following, then apply all effects, or verify one failing condition and change
**nothing**:

- `a != b`.
- Both cells are registered and alive (not `DEAD`).
- Both cells are sexual.
- Both cells are in `REPRODUCING` state.
- Both cells remain registered at their current map positions.
- The partner is within the configured field of view, and the cells are close
  enough to pair (recommended: Chebyshev distance of 1, i.e. adjacent;
  document the choice).
- The population is below `maxPopulation`.
- A free position for the offspring exists near the parents (search the
  neighborhoods of both parents, bounded).

If any condition fails, return a failure result **with no state changes**
(no partial reset, no half-registered offspring). If there is no free position
for the offspring, both parents remain in `REPRODUCING` mode and retry later.
No global fallback match is performed.

If all conditions hold, apply these effects atomically in the same critical
section:

- Create one hungry sexual offspring (via the factory) and register it at the
  chosen free position.
- Reset both parents' meal counters and return both parents to `HUNGRY`, so
  successful reproduction does not leave them locked in reproducing mode. Since
  both parents leave `REPRODUCING` in the same critical section, any other
  thread concurrently attempting to pair with either of them will fail
  validation afterwards. This makes double pairing impossible, including:
  - **Both partners initiating at once** (A pairs with B while B pairs with A):
    the lock serializes them; the second call sees the cells no longer
    `REPRODUCING` and fails harmlessly.
  - **Three-way contention** (A, B, C all want each other): exactly one pair
    can succeed; the third fails validation and continues searching.
- Increment the birth counter.

After the critical section returns (lock released), the **engine** starts the
offspring's task:

- If the task is accepted, nothing more to do.
- If starting it fails (e.g. `RejectedExecutionException` because shutdown has
  begun), call `rollbackBirth(offspring)`, which removes the offspring from the
  map and decrements the birth counter inside the lock, so no cell remains
  registered without a running task. Births occurring during shutdown are
  simply discarded this way.

Neither parent should block in `MatingPool` while waiting for the other cell.
The pair search and claim should be a non-blocking operation; failure simply
ends the current action and allows both cells to continue their normal loops.

`MatingPool` can be removed if the map provides the required atomic pairing
operation (which this plan specifies). Preferred: remove it. If it is kept
temporarily, reduce it to a thin delegate with **no waiting, no condition
variables, and no global waiting partner**, and ensure it cannot hold any lock
while calling into the map.

---

## 9. Asexual reproduction

Preserve the existing behavior in `AsexualCell`:

- After ten meals, create two hungry asexual cells. The offspring must be
  initialized with `CellState.HUNGRY` and must immediately participate in the
  same non-blocking movement/feeding loop as every other cell.
- Place the offspring in valid free positions near the parent.
- Reset the parent meal counter and return the parent to its normal active
  lifecycle; do not leave it blocked in a reproduction stage.

Implement as one atomic `tryAsexualReproduce(parent, offspringFactory)`
operation:

- Inside the critical section, verify the parent is registered, alive, and in
  the reproduction-eligible state; verify
  `population + 2 <= maxPopulation`; find **two** distinct free positions near
  the parent. **Reserve both or neither.** Never register one offspring and
  then discover the second slot is gone.
- If two free positions are not available (or the population cap would be
  exceeded), defer reproduction rather than creating cells without positions.
  The operation returns a failure result and changes **nothing**; the parent
  remains in reproducing mode or returns to its normal active lifecycle
  according to the selected implementation, and retries on a later action
  interval. It must not block waiting for positions.
- On success, in the same critical section: register both offspring, reset the
  parent's meal counter, return the parent to `HUNGRY` so it must find food
  again rather than continuing from a fed state, and add 2 to births.

After the critical section, the engine starts both offspring tasks (outside
the lock). If starting either fails, call `rollbackBirth` for any offspring
that did not start (see Section 8). The parent must have been reset only once,
and the meal/reproduction bookkeeping must not be applied twice.

---

## 10. Simulation engine changes

Update `SimulationEngine` to own:

- The XY map.
- The cell registry.
- Cell and movement scheduling.
- Food initialization.
- Birth, death, and meal counters (held by the map under its lock, Section
  0.7; the engine reads them through `snapshot()`).
- Statistics logging.
- Shutdown behavior.

Recommended lifecycle:

```text
construct engine
  -> validate configuration
  -> construct map
  -> populate initial food
  -> create and position initial cells

start
  -> start cell tasks
  -> start food/statistics schedulers if retained

run
  -> cells move, feed, starve, and reproduce

shutdown
  -> mark engine as stopping (volatile flag; map rejects new births)
  -> stop schedulers
  -> wake/interrupt cell tasks
  -> await task termination with a bounded timeout
  -> remove or finalize active cells
  -> write final statistics (exactly once)
```

The existing executor-based threaded model can remain in place. Synchronize
world-map mutations and avoid assuming that task execution order is
deterministic.

### 10.1 Task lifecycle and thread bounds

- Every registered cell has exactly one running (or about-to-run) task, and
  every running task's cell is registered or exits. The only gap is the brief
  interval between registration inside the lock and task submission after it,
  which is handled by `rollbackBirth` on submission failure.
- Thread count is bounded by `maxPopulation` (Section 2). The executor type
  and size must be consistent with that bound; a saturated executor must never
  silently queue a cell task that will not run.
- Cell tasks submitted by other cell threads (offspring) go through the same
  executor; handle `RejectedExecutionException` explicitly.

### 10.2 Shutdown ordering

The shutdown sequence must be safe against in-flight births and deaths:

1. Set a `volatile boolean stopping` flag. `tryPairAndReproduce` and
   `tryAsexualReproduce` check it **inside** the critical section and refuse
   births once set. This closes the race between shutdown and offspring
   creation.
2. Stop the food/statistics schedulers (no new periodic work).
3. Interrupt the cell tasks (`shutdownNow`) and await termination with a
   bounded timeout. Cell loops exit on interrupt or when the stop flag is
   observed.
4. After tasks have terminated, finalize remaining cells under the lock
   (clear map state), so no task can be modifying it.
5. Take the final `snapshot()` and write the final statistics row **once**.
6. Close the CSV writer last.

Calling `shutdown()` twice must be safe and idempotent (guard with an
`AtomicBoolean`).

---

## 11. Resource manager decision

`ResourceManager` currently represents a global pool of food units. It should
no longer control food availability after the map is introduced.

Preferred approach:

- Remove `ResourceManager`.
- Move food operations into the XY map.

If removing it immediately would create unnecessary compatibility work, keep
it temporarily as a deprecated adapter, but ensure all simulation behavior
uses map positions rather than its global counter. The adapter must not be a
second source of truth for the food count, must not be read by statistics,
and must not block any thread.

---

## 12. Statistics

Keep the existing CSV fields initially:

```text
timestamp_ms,population,food,births,deaths,meals
```

Here, `food` should represent the number of food positions currently present
on the map.

Concurrency requirements:

- Every row is built from **one** `WorldMap.snapshot()` call, taken inside the
  map lock, so `population`, `food`, `births`, `deaths`, and `meals` are all
  consistent with one another and with Section 0.7's invariant. Never read
  these values piecemeal from different sources.
- The snapshot is an immutable record. Formatting and file I/O happen
  **outside** the lock.
- The CSV writer is accessed by the statistics scheduler thread and the
  shutdown path. Protect it with its own lock (a separate monitor from the map
  lock, never nested inside it) and a `closed` flag so late writes after close
  are skipped rather than throwing or interleaving.
- Timestamp is taken at (or immediately around) snapshot time.

Optional spatial fields can be added later:

- `sexual_population`
- `asexual_population`
- `reproducing_population`
- `movement_count` (use `LongAdder` or count it under the map lock)
- `average_x`
- `average_y`

Do not include a seed or determinism metadata because random behavior is
intentionally not reproducible between runs.

---

## 13. Test updates

Update `SimulationTest` with tests for the following. Tests assert valid
behavior and invariants, **not** exact random positions or exact event
sequences.

### XY map (single-threaded unit tests)

- Adding and removing food at coordinates.
- Rejecting duplicate food positions.
- Registering and removing cells.
- Rejecting out-of-bounds moves.
- Rejecting moves into occupied positions.
- Consuming food at a cell position.
- Atomic position updates.
- `checkInvariants()` passes after every operation.
- Operations on dead or unregistered cells are rejected without side effects.
- Killing a cell twice has the effect of killing it once (idempotent death).
- Bounded food spawn on a full or nearly full map places fewer than requested
  and does not hang.

### Initialization

- The requested number of food positions is created.
- Food positions are unique.
- Initial cells are placed on valid, distinct positions.
- Initialization occurs before cell tasks start.
- Invalid map/population configurations fail clearly.
- Initialization on a nearly full map terminates (no unbounded retry loop).

### Field of view

- Food inside the FOV is found.
- Food outside the FOV is ignored.
- Reproducing sexual cells inside the FOV are found.
- Non-reproducing or incompatible cells are ignored.
- Map boundaries are handled correctly.
- Returned collections are immutable snapshots (mutating the map afterwards
  does not change a previously returned list, and iterating one during map
  mutation does not throw).

### Movement

- A cell moves toward visible food.
- A cell moves toward a visible reproducing sexual partner.
- A sexual cell searches for a partner while reproducing.
- A cell makes a valid random move when no target is visible.
- A blocked or boundary move does not corrupt map state.

### Reproduction

- Asexual reproduction creates two positioned offspring.
- Asexual reproduction with fewer than two free positions creates nothing and
  changes no state (all-or-nothing).
- Sexual reproduction requires two nearby compatible cells.
- A successful pair creates exactly one offspring.
- A cell cannot be paired twice at the same time.
- Offspring placement handles a full neighborhood.
- Reproduction is deferred, not failed, when `maxPopulation` is reached.
- `rollbackBirth` fully undoes registration and the birth count.

### Concurrency and stress tests (required)

Use `CountDownLatch`/`CyclicBarrier`/`ExecutorService` to start contenders
simultaneously and force the race. Use bounded timeouts on every wait so a
deadlock fails the test instead of hanging it. Repeat contended scenarios many
times (e.g. hundreds of iterations) to expose rare interleavings. Do **not**
rely on `Thread.sleep` for correctness or ordering.

- **N cells, one destination:** many threads try to move into the same free
  square; exactly one succeeds, the rest get `OCCUPIED`, and the map invariants
  hold.
- **One food, many eaters:** many cells enter/eat the same food position
  simultaneously; exactly one meal is recorded.
- **Simultaneous mutual pairing:** A and B both call
  `tryPairAndReproduce` against each other; exactly one offspring is created
  and both parents end `HUNGRY` with reset meal counters.
- **Three-way contention:** three reproducing cells all attempt pairing;
  exactly one pair succeeds.
- **Pairing vs. death:** one thread kills a cell while another pairs with it;
  the result is either "paired then alive" or "dead, pairing failed", never a
  dead parent that produced offspring or a corrupted index.
- **Double death:** two threads kill the same cell; death count increases by
  exactly one and at most one food batch is spawned.
- **Starvation vs. eating:** a cell eats right as its deadline passes; it
  either dies once or survives with reset timers, never both.
- **Asexual reproduction contention:** parents competing for the same free
  neighborhood never create a cell without a position nor leak a reserved
  position.
- **Offspring placement vs. movement:** movers and reproducers run
  concurrently; no position ever holds two cells.
- **Randomized stress:** run a mixed workload of moves, eats, deaths, births,
  and food spawns from many threads for a fixed number of operations and call
  `checkInvariants()` repeatedly (and at the end). Verify
  `population == initial + births - deaths`.
- **Shutdown races:** shut down while births and deaths are in flight;
  shutdown completes within a bounded time, no registered cell remains without
  being finalized, the final statistics row is written exactly once, and no
  threads are leaked (check thread count or executor termination).
- **Statistics consistency:** sample `snapshot()` repeatedly during a running
  simulation and assert each snapshot satisfies the Section 0.7 invariant.
- **Visibility:** cell state read by another thread reflects writes made
  under the lock (covered implicitly by the stress test; do not use
  non-volatile fields in tests to "check").

### End-to-end behavior

- The map starts with food.
- Cells can move and consume map food.
- Statistics report map food count.
- Cells can die after starvation.
- Existing CSV creation and shutdown behavior remain valid.
- A full short simulation run (several seconds with many cells) completes
  without exceptions, deadlock, or invariant violation.

---

## 14. Recommended implementation order

1. Add `Position`.
2. Implement the thread-safe XY map **with the single lock, composite
   operations (Section 1.3), immutable snapshot queries, and
   `checkInvariants()`**, plus map-focused unit tests **and the first map-level
   concurrency tests** (contended move, contended eat, idempotent death).
3. Add map dimensions, food count, FOV, movement interval, and
   `maxPopulation` configuration.
4. Move initial food generation into the map initialization phase (bounded
   shuffle-based placement).
5. Add positions to cells (map-owned) and make shared `Cell` fields
   `volatile`/lock-guarded; register initial cells on the map.
6. Replace global food consumption with coordinate-based atomic
   move-and-eat and eat-at-current-position.
7. Implement random movement and movement toward visible food, with the
   non-blocking, interruptible action loop and the `tryStarve`
   re-validation.
8. Implement spatial target selection for sexual cells.
9. Replace global mating with atomic field-of-view partner pairing
   (`tryPairAndReproduce`), including mutual-initiation and three-way
   contention tests.
10. Update asexual and sexual offspring placement (all-or-nothing reservation,
    post-lock task start, `rollbackBirth`, population cap).
11. Remove or deprecate the old global `ResourceManager` and global mating
    logic.
12. Update statistics (single-snapshot rows, guarded CSV writer) and
    `Main.java`; implement the shutdown ordering of Section 10.2.
13. Replace obsolete resource-pool and mating tests; add the remaining
    concurrency/stress/shutdown tests from Section 13.
14. Run the complete test harness (repeat the concurrency tests multiple
    times) and an end-to-end simulation; confirm no invariant violations,
    deadlocks, or leaked threads.

---

## Appendix: Concurrency review checklist

Use this before considering any step complete.

- [ ] All world state is mutated only inside the map's single lock.
- [ ] No public map method requires the caller to do check-then-act.
- [ ] Every map operation re-validates preconditions (registered, alive, in
      bounds, free, state) inside its critical section.
- [ ] A failed operation changes **nothing** (no partial effects).
- [ ] Queries return immutable snapshots; no live internal collections or
      `Cell` references leak out.
- [ ] No sleeping, I/O, waiting, or executor submission occurs while holding
      the lock.
- [ ] Position has one owner (the map); `Cell` never writes it unprotected.
- [ ] Cross-thread `Cell` fields are `volatile`/atomic or lock-guarded.
- [ ] Death is idempotent and counted once; `DEAD` is terminal.
- [ ] Starvation is decided inside the lock by re-reading the deadline.
- [ ] Food consumption and meal/timer updates are one atomic step.
- [ ] Pairing validates and applies in one critical section; both parents
      leave `REPRODUCING` together.
- [ ] Asexual reproduction reserves both positions or none.
- [ ] Offspring tasks start after the lock is released; submission failure
      calls `rollbackBirth`.
- [ ] Thread count is bounded by `maxPopulation`; reproduction defers at the
      cap.
- [ ] `InterruptedException` restores the interrupt flag and exits cleanly.
- [ ] Statistics rows come from one snapshot; CSV writer is guarded and
      closed once.
- [ ] Shutdown is idempotent, refuses new births, and awaits task termination
      with a timeout.
- [ ] `ThreadLocalRandom.current()` is called at each use site, never cached
      across threads.
- [ ] Concurrency tests use latches/barriers and timeouts (no sleep-based
      assertions) and run repeatedly.
