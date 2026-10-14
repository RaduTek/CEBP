import com.gameoflife.SimulationEngine;

/**
 * Entry point of the Game of Life simulation. All timing and population
 * parameters are configured here.
 */
public class Main {

    // Survival timings.
    private static final long T_FULL_MS = 1_000;      // time a meal sustains a cell
    private static final long T_STARVE_MS = 2_000;   // time a hungry cell survives without food

    // Reproduction.
    private static final long MATING_TIMEOUT_MS = 3_000;

    // Environment.
    private static final int FOOD_CAPACITY = 200;
    private static final int INITIAL_FOOD = 50;
    private static final long FOOD_GENERATION_INTERVAL_MS = 2_000;
    private static final int FOOD_GENERATION_AMOUNT = 3;

    // Initial population.
    private static final int INITIAL_SEXUAL_CELLS = 4;
    private static final int INITIAL_ASEXUAL_CELLS = 4;

    // Statistics.
    private static final long STATS_INTERVAL_MS = 500;
    private static final String STATS_FILE_NAME = "statistics.csv";

    // Total simulation duration (may be overridden by the first CLI argument).
    private static final long DEFAULT_DURATION_MS = 30_000;

    public static void main(String[] args) throws InterruptedException {
        long durationMs = DEFAULT_DURATION_MS;
        if (args.length > 0) {
            durationMs = Long.parseLong(args[0]);
        }

        SimulationEngine.Config config = new SimulationEngine.Config(
                T_FULL_MS,
                T_STARVE_MS,
                MATING_TIMEOUT_MS,
                STATS_INTERVAL_MS,
                FOOD_GENERATION_INTERVAL_MS,
                FOOD_GENERATION_AMOUNT,
                FOOD_CAPACITY,
                INITIAL_FOOD,
                INITIAL_SEXUAL_CELLS,
                INITIAL_ASEXUAL_CELLS,
                STATS_FILE_NAME);

        SimulationEngine engine = new SimulationEngine(config);

        System.out.println("Starting Game of Life simulation for " + durationMs + " ms...");
        engine.run(durationMs);

        System.out.println();
        System.out.println("=== Simulation summary ===");
        System.out.println("Final population : " + engine.getPopulation());
        System.out.println("Total births      : " + engine.getBirths());
        System.out.println("Total deaths      : " + engine.getDeaths());
        System.out.println("Food remaining    : " + engine.getResources().getAvailableFood());
        System.out.println("Statistics written to " + STATS_FILE_NAME);
    }
}
