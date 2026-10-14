package com.gameoflife;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain-Java test harness (no JUnit on this machine). Run with:
 *
 * <pre>java -cp out com.gameoflife.SimulationTest</pre>
 */
public class SimulationTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testResourceManagerBasics();
        testResourceManagerConcurrent();
        testMatingPoolPairing();
        testMatingPoolTimeout();
        testAsexualReproduction();
        testEndToEndRun();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // --- ResourceManager -------------------------------------------------

    private static void testResourceManagerBasics() throws Exception {
        ResourceManager rm = new ResourceManager(10, 2);
        check("initial food", rm.getAvailableFood() == 2);
        check("consume succeeds", rm.tryConsume(0));
        check("consume second", rm.tryConsume(0));
        check("pool empty", rm.getAvailableFood() == 0);
        check("consume times out", !rm.tryConsume(50));
        rm.addFood(5);
        check("food added", rm.getAvailableFood() == 5);
        rm.addFood(100);
        check("capacity cap", rm.getAvailableFood() == 10);
    }

    /** 8 consumer threads race for 4 units; exactly 4 must succeed. */
    private static void testResourceManagerConcurrent() throws Exception {
        ResourceManager rm = new ResourceManager(10, 4);
        int consumers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(consumers);
        AtomicInteger successes = new AtomicInteger();
        for (int i = 0; i < consumers; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (rm.tryConsume(200)) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        check("concurrent consumers finish", done.await(5, TimeUnit.SECONDS));
        check("exactly 4 meals served", successes.get() == 4);
        check("pool drained", rm.getAvailableFood() == 0);
    }

    // --- MatingPool ------------------------------------------------------

    private static void testMatingPoolPairing() throws Exception {
        MatingPool pool = new MatingPool();
        Cell a = new SexualCell(engineStub());
        Cell b = new SexualCell(engineStub());

        CountDownLatch waiterDone = new CountDownLatch(1);
        AtomicInteger waiterResult = new AtomicInteger(-1);

        Thread waiter = new Thread(() -> {
            try {
                if (pool.tryRegisterAsWaiter(a)) {
                    Cell partner = pool.awaitPartner(a, 5_000);
                    waiterResult.set(partner == null ? 0 : (int) partner.getId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                waiterDone.countDown();
            }
        });
        waiter.start();

        // Give the waiter a moment to register before arriving.
        Thread.sleep(100);
        Cell matched = pool.arriveAndMatch(b);
        check("arrival matches waiter", matched == a);
        check("waiter finishes", waiterDone.await(5, TimeUnit.SECONDS));
        check("waiter got arrival id", waiterResult.get() == b.getId());
        check("pool empty after match", pool.arriveAndMatch(b) == null);
    }

    private static void testMatingPoolTimeout() throws Exception {
        MatingPool pool = new MatingPool();
        Cell a = new SexualCell(engineStub());
        check("tryRegister works", pool.tryRegisterAsWaiter(a));
        long t0 = System.nanoTime();
        Cell result = pool.awaitPartner(a, 200);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        check("awaitPartner times out", result == null);
        check("timeout roughly respected", elapsedMs >= 150);
        check("pool empty after timeout", pool.arriveAndMatch(a) == null);
    }

    // --- Asexual reproduction --------------------------------------------

    private static void testAsexualReproduction() throws Exception {
        SimulationEngine engine = testEngine();
        AsexualTestCell cell = new AsexualTestCell(engine);
        engine.submitCell(cell);
        int before = engine.getBirths();
        boolean reproduced = cell.reproducePublic();
        check("asexual division succeeds", reproduced);
        check("asexual division births 2", engine.getBirths() == before + 2);
        engine.shutdown();
    }

    // --- End-to-end -------------------------------------------------------

    private static void testEndToEndRun() throws Exception {
        Path csv = Path.of("test-statistics.csv");
        Files.deleteIfExists(csv);

        SimulationEngine.Config config = new SimulationEngine.Config(
                /* tFullMs */ 100,
                /* tStarveMs */ 300,
                /* matingTimeoutMs */ 300,
                /* statsIntervalMs */ 100,
                /* foodGenerationIntervalMs */ 200,
                /* foodGenerationAmount */ 2,
                /* foodCapacity */ 100,
                /* initialFood */ 10,
                /* initialSexualCells */ 3,
                /* initialAsexualCells */ 3,
                /* statsFileName */ csv.toString());
        SimulationEngine engine = new SimulationEngine(config);
        engine.run(2_000);

        check("CSV file created", Files.exists(csv));
        List<String> lines = Files.readAllLines(csv);
        check("CSV has header", lines.get(0).equals(
                "timestamp_ms,population,food,births,deaths,meals"));
        check("CSV has data rows", lines.size() > 1);
        check("births happened", engine.getBirths() > 0);
        check("population tracked", engine.getPopulation() >= 0);
        Files.deleteIfExists(csv);
    }

    // --- Helpers ----------------------------------------------------------

    /** Minimal engine usable without running the full simulation. */
    private static SimulationEngine testEngine() {
        SimulationEngine.Config config = new SimulationEngine.Config(
                100, 100, 100, 1_000, 0, 0, 50, 10, 0, 0, "test-stats.csv");
        return new SimulationEngine(config);
    }

    /** Exposes the protected reproduce() hook for testing. */
    private static class AsexualTestCell extends AsexualCell {
        AsexualTestCell(SimulationEngine engine) {
            super(engine);
        }

        boolean reproducePublic() {
            return reproduce();
        }
    }

    private static SimulationEngine engineStub() {
        return testEngine();
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}