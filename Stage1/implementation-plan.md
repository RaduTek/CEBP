# Implementation Plan: Concurrent Game of Life in Java

This document outlines the architecture, concurrency model, and implementation details for the Concurrent Game of Life simulation using basic Java threading features (`java.lang.Thread`, `synchronized`, `wait`/`notifyAll`, `ReentrantLock`, `BlockingQueue`, etc.).

---

## 1. System Overview & Core Entities

### A. **Resource Manager (Food Pool)**
- **Role**: Manages a global/local pool of available food units.
- **State**: `currentFoodCount`, maximum capacity, mutex lock.
- **Operations**:
  - `consumeFood()`: Threads attempt to acquire a food unit. If none are available, cells block/wait until food is replenished (either via cell death or periodic environmental generation).
  - `releaseFood(int amount)`: When a cell dies, it releases between 1 and 5 food units back into the pool, notifying waiting cells.

### B. **Cell (Thread-based Agent)**
Each cell runs in its own distinct thread (`extends Thread` or `implements Runnable`).
- **Attributes & State**:
  - `id`: Unique identifier.
  - `type`: `SEXUAL` or `ASEXUAL`.
  - `state`: `ALIVE`, `HUNGRY`, `DEAD`, `REPRODUCING`.
  - `mealsEaten`: Counter tracking successful feeds (triggers reproduction after 10 meals).
  - `lastFedTimestamp`: Tracks $T_{\text{full}}$ and $T_{\text{starve}}$ timeouts.
  - `simulationBoard`: Reference to the shared space/grid or registry where cells coordinate.

---

## 2. Concurrency Model & Synchronization Strategy

To implement this robustly with Java's concurrency utilities:
1. **`ExecutorService` & Thread Pools**: We will use a managed thread pool (such as `Executors.newCachedThreadPool()` or a custom bounded thread pool) to execute cell tasks. This efficiently manages thread lifecycles and reuse.
2. **`Runnable` Cell Tasks**: The `Cell` class will implement the `Runnable` interface, allowing each cell instance to be submitted to the executor service.
3. **Monitors (`synchronized`, `wait()`, `notifyAll()`)** or **Explicit Locks (`ReentrantLock`, `Condition`)**:
   - Used for the shared Resource Pool to manage blocking when food runs out.
   - Used for cell-to-cell mating encounters.
4. **Thread-Safe Collections (`ConcurrentHashMap`, `CopyOnWriteArrayList`, `BlockingQueue`)**:
   - To maintain the registry of active cells on the board/simulation arena so sexual cells can find mates.

---

## 3. Detailed Lifecycle of a Cell Task (`Runnable`)

```
[Submitted to ExecutorService] ---> Attempt to Consume Food
                                         |
                       +-----------------+-----------------+
                       |                                   |
                   (Success)                       (No Food Available)
                       |                                   |
                Reset Timers               Wait on Food Monitor (`wait()`)
         Feed Count++                                      |
                       | <---------------------------------+
                       v
           Is Feed Count >= 10?
              /           \
           (Yes)          (No)
            /               \
       Reproduce          Normal Living State
        /       \
    (Asexual)  (Sexual) -> Search for partner in active registry -> Submit offspring to ExecutorService
```

### 1. **Feeding & Survival Timers**
- Each cell implements `Runnable` and executes in a loop managed by the `ExecutorService`:
  1. **Fed State**: Lasts for duration $T_{\text{full}}$. The cell consumes food immediately upon birth or after eating.
  2. **Hungry State**: Entered after $T_{\text{full}}$ expires. The cell actively tries to consume a food unit within time window $T_{\text{starve}}$.
  3. **Starvation/Death**: If $T_{\text{starve}}$ elapses without securing food, the cell terminates (dies), releasing $1 \text{ to } 5$ food units back to the Resource Manager using `synchronized` blocks and `notifyAll()`.

### 2. **Reproduction Mechanism (After $\ge 10$ Meals)**
- **Asexual Reproduction**:
  - Cell divides into two new hungry cell tasks and submits them to the `ExecutorService`.
  - Resets its meal counter.
- **Sexual Reproduction**:
  - Cell signals readiness to reproduce and registers itself in a thread-safe matching pool.
  - Upon finding a partner, a third cell is created and submitted to the `ExecutorService`. Both parents reset their meal counters.

---

## 4. Simulation Arena & Coordination

- **Simulation Runner (`SimulationEngine`)**:
  - Initializes the shared resource pool with initial food units.
  - Manages an `ExecutorService` (e.g., `Executors.newCachedThreadPool()`) to run cell tasks concurrently.
  - Submits initial population of sexual and asexual cell tasks.
  - Periodically logs statistics (active cell count, food units, deaths, births) into CSV files, which can later be used to make charts or for statistical analysis.
  - Handles graceful shutdown and termination of the executor service when the simulation ends.

---

## 5. Proposed Project Structure

```text
src/
├── Main.java                          # Entry point and configuration
└── com/gameoflife/
    ├── SimulationEngine.java          # Orchestrates ExecutorService and resources
    ├── Cell.java                      # Implements Runnable representing a cell task
    ├── AsexualCell.java               # Subclass for asexual behavior
    ├── SexualCell.java                # Subclass for sexual behavior
    ├── CellType.java                   # Enum (SEXUAL, ASEXUAL)
    ├── CellState.java                  # Enum (ALIVE, HUNGRY, REPRODUCING, DEAD)
    ├── ResourceManager.java           # Thread-safe food pool management
    └── MatingPool.java                # Thread-safe partner matching for sexual cells
test/
└── com/gameoflife/
    └── SimulationTest.java            # Unit/concurrency tests
