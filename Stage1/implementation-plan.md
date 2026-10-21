# Implementation Plan: XY-Map Cell Simulation

This plan describes the reimplementation of the cell simulation around an
XY map containing cells and food. The current threaded execution model is
retained. Random behavior continues to use Java's `ThreadLocalRandom`; the
simulation does not attempt to produce identical results between runs.

## 1. Spatial model

Add a coordinate value type:

```java
public record Position(int x, int y) {}
```

Add a thread-safe world/map component responsible for:

- Map width and height.
- Food positions.
- Cell positions.
- Registering and removing cells.
- Moving cells within map boundaries.
- Finding food in a cell's field of view.
- Finding compatible cells in a cell's field of view.
- Consuming food at a cell's current position.

Recommended initial representation:

```text
Set<Position> foodPositions
Map<Long, Position> cellPositions
Map<Position, Cell> cellsByPosition
```

The map should define the collision policy explicitly. The recommended
behavior is:

- One cell per position.
- Food may share a position with a cell.
- A move outside the map is rejected.
- A move to an occupied position is rejected.
- Food is consumed when a cell successfully enters or occupies its position.

All compound operations that update both cell and position indexes must be
atomic.

## 2. Simulation configuration

Extend `SimulationEngine.Config` with spatial settings:

```java
int mapWidth;
int mapHeight;
int initialFoodCount;
int fieldOfView;
long minMoveIntervalMs;
long maxMoveIntervalMs;
```

Do not add a random seed parameter. Random choices should continue using
`ThreadLocalRandom.current()`.

Validate:

- Map dimensions are positive.
- `initialFoodCount` is non-negative and does not exceed map capacity.
- `fieldOfView` is non-negative.
- `minMoveIntervalMs` is non-negative.
- `maxMoveIntervalMs >= minMoveIntervalMs`.

Keep the existing lifecycle, starvation, reproduction, statistics, and
population settings unless they are changed as a consequence of introducing
the map.

## 3. Initializing the map and food

Add an explicit initialization phase before any cell tasks are submitted:

1. Construct the XY map.
2. Select `initialFoodCount` random, unique positions.
3. Add food to those positions.
4. Create the initial sexual and asexual cells with `CellState.HUNGRY`.
5. Assign each cell a valid random starting position.
6. Register each cell in the map and engine registry.
7. Start the cell tasks and schedulers.

Food must be present before cells start executing. Initial food placement can
use repeated `ThreadLocalRandom.current().nextInt(...)` calls until a free
food position is found.

Initial cell placement should avoid occupied positions. If the requested
population exceeds available map positions, fail configuration validation
with a clear `IllegalArgumentException`.

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

## 5. Cell positions and non-blocking movement

Update `Cell` to maintain its current `Position`. Every cell should start in
the `HUNGRY` state, including the initial population and all offspring.

Cells must not block while waiting for a specific lifecycle stage, food,
partner, or reproduction opportunity. A cell should remain an active task that
periodically performs one short action and then yields until its next action.
The action loop should be interruptible during shutdown.

At each random movement/action interval:

1. Check whether the cell is still alive.
2. Determine its current target-selection mode.
3. Search the map for the appropriate target in its field of view.
4. If a target exists, move one step toward it.
5. Otherwise, select a random neighboring position.
6. Ask the map to perform the move atomically.
7. Consume food if the new position contains food.
8. Update the cell state and timers without waiting for another stage.
9. Return to the loop and schedule the next action.

Movement should use the eight neighboring positions, excluding the current
position. Invalid and occupied destinations should be ignored. If no valid
destination exists, the cell remains in place and tries again later.

For a visible target, calculate the step independently on each axis:

```text
stepX = sign(target.x - current.x)
stepY = sign(target.y - current.y)
```

If more than one target is visible, select one according to the map's
ordering or choose randomly with `ThreadLocalRandom`.

The movement interval should be selected from the configured range. If
`minMoveIntervalMs == maxMoveIntervalMs`, use that fixed interval.

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
with map-based food consumption:

```java
worldMap.consumeFoodAt(cell.getPosition())
```

The cell becomes `HUNGRY` after `tFullMs`. It must reach food and consume it
within `tStarveMs`, otherwise it dies. Consuming food immediately resets the
fullness timer, clears the starvation deadline, increments the meal count, and
returns the cell to `ALIVE` unless reproduction is triggered.

When a cell dies:

1. Mark it as `DEAD`.
2. Remove it from the world map.
3. Remove it from the engine registry.
4. Increment the death count.
5. Optionally place one to five new food items at random free positions if
   the original death-food rule is retained.

If death releases food, use `ThreadLocalRandom.current().nextInt(1, 6)` and
make the map insertion explicit. Do not use the old global resource count as
the source of truth.

## 7. Normal and sexual target selection

Normal cells search for food:

```text
target = nearest or selected food position in the field of view
```

Sexual cells search for food until they reach the reproduction threshold.
After reaching that threshold:

1. Set the state to `REPRODUCING`.
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

## 8. Spatial sexual reproduction

Replace the current global waiting behavior in `MatingPool` with spatial
matching.

The map or a mating coordinator must atomically claim a pair so that two
threads cannot use the same cell simultaneously. Before pairing, verify that:

- Both cells are alive.
- Both cells are sexual.
- Both cells are in `REPRODUCING` state.
- Both cells remain registered at their expected positions.
- The partner is within the configured field of view.

After pairing:

- Create one hungry sexual offspring.
- Place the offspring in a valid free position near the parents if possible.
- Reset both parents' meal counters and return both parents to `HUNGRY`, so
  successful reproduction does not leave them locked in reproducing mode.
- Record the new birth.

If the pair has no free position for the offspring, leave both parents in
reproducing mode and retry on a later movement event. No global fallback
match should be performed.

Neither parent should block in `MatingPool` while waiting for the other cell.
The pair search and claim should be a non-blocking operation; failure simply
ends the current action and allows both cells to continue their normal loops.

`MatingPool` can be removed if the map provides the required atomic pairing
operation. Otherwise, reduce it to a lock/coordinator for claiming spatial
pairs rather than maintaining a global waiting partner.

## 9. Asexual reproduction

Preserve the existing behavior in `AsexualCell`:

- After ten meals, create two hungry asexual cells. The offspring must be
  initialized with `CellState.HUNGRY` and must immediately participate in the
  same non-blocking movement/feeding loop as every other cell.
- Place the offspring in valid free positions near the parent.
- Reset the parent meal counter and return the parent to its normal active
  lifecycle; do not leave it blocked in a reproduction stage.

If two free positions are not available, defer reproduction rather than
creating cells without positions. The parent remains in reproducing mode or
returns to its normal active lifecycle according to the selected
implementation, and retries on a later action interval. It must not block
waiting for positions. Once the offspring are created, the parent resets its
meal counter and returns to `HUNGRY` so it must find food again rather than
continuing from a fed state.

## 10. Simulation engine changes

Update `SimulationEngine` to own:

- The XY map.
- The cell registry.
- Cell and movement scheduling.
- Food initialization.
- Birth, death, and meal counters.
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
  -> stop schedulers
  -> wake/interrupt cell tasks
  -> remove or finalize active cells
  -> write final statistics
```

The existing executor-based threaded model can remain in place. Synchronize
world-map mutations and avoid assuming that task execution order is
deterministic.

## 11. Resource manager decision

`ResourceManager` currently represents a global pool of food units. It should
no longer control food availability after the map is introduced.

Preferred approach:

- Remove `ResourceManager`.
- Move food operations into the XY map.

If removing it immediately would create unnecessary compatibility work, keep
it temporarily as a deprecated adapter, but ensure all simulation behavior
uses map positions rather than its global counter.

## 12. Statistics

Keep the existing CSV fields initially:

```text
timestamp_ms,population,food,births,deaths,meals
```

Here, `food` should represent the number of food positions currently present
on the map.

Optional spatial fields can be added later:

- `sexual_population`
- `asexual_population`
- `reproducing_population`
- `movement_count`
- `average_x`
- `average_y`

Do not include a seed or determinism metadata because random behavior is
intentionally not reproducible between runs.

## 13. Test updates

Update `SimulationTest` with tests for:

### XY map

- Adding and removing food at coordinates.
- Rejecting duplicate food positions.
- Registering and removing cells.
- Rejecting out-of-bounds moves.
- Rejecting moves into occupied positions.
- Consuming food at a cell position.
- Atomic position updates.

### Initialization

- The requested number of food positions is created.
- Food positions are unique.
- Initial cells are placed on valid, distinct positions.
- Initialization occurs before cell tasks start.
- Invalid map/population configurations fail clearly.

### Field of view

- Food inside the FOV is found.
- Food outside the FOV is ignored.
- Reproducing sexual cells inside the FOV are found.
- Non-reproducing or incompatible cells are ignored.
- Map boundaries are handled correctly.

### Movement

- A cell moves toward visible food.
- A cell moves toward a visible reproducing sexual partner.
- A sexual cell searches for a partner while reproducing.
- A cell makes a valid random move when no target is visible.
- A blocked or boundary move does not corrupt map state.

### Reproduction

- Asexual reproduction creates two positioned offspring.
- Sexual reproduction requires two nearby compatible cells.
- A successful pair creates exactly one offspring.
- A cell cannot be paired twice at the same time.
- Offspring placement handles a full neighborhood.

### End-to-end behavior

- The map starts with food.
- Cells can move and consume map food.
- Statistics report map food count.
- Cells can die after starvation.
- Existing CSV creation and shutdown behavior remain valid.

Tests should assert valid behavior and invariants, not exact random positions or
exact event sequences.

## 14. Recommended implementation order

1. Add `Position`.
2. Implement the thread-safe XY map and map-focused tests.
3. Add map dimensions, food count, FOV, and movement interval configuration.
4. Move initial food generation into the map initialization phase.
5. Add positions to cells and register initial cells on the map.
6. Replace global food consumption with coordinate-based consumption.
7. Implement random movement and movement toward visible food.
8. Implement spatial target selection for sexual cells.
9. Replace global mating with field-of-view partner pairing.
10. Update asexual and sexual offspring placement.
11. Remove or deprecate the old global `ResourceManager` and global mating logic.
12. Update statistics and `Main.java`.
13. Replace obsolete resource-pool and mating tests.
14. Run the complete test harness and end-to-end simulation.
