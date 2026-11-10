package io.github.carefreebit.jvmd.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Reproducible incidents used by {@code jvmd demo}. Every scenario starts real threads in the
 * current JVM, so {@code jcmd} attaches to it exactly like it would to a production process.
 */
public final class DemoApp {

    private static final AtomicLong SINK = new AtomicLong();

    private DemoApp() {
    }

    public static List<String> scenarios() {
        return List.of("deadlock", "cpu-spin", "thread-leak", "lock-contention",
                "pool-starvation", "downstream-block");
    }

    public static String describe(String scenario) {
        return switch (scenario) {
            case "deadlock" -> "Two order workers grab the same two monitors in opposite order.";
            case "cpu-spin" -> "One busy stack on every core: a hash loop that never yields.";
            case "thread-leak" -> "520 order-sync workers parked forever behind a latch.";
            case "lock-contention" -> "One slow owner plus 40 Tomcat workers queued on the same monitor.";
            case "pool-starvation" -> "A leaked connection holds the pool lock; 12 HTTP workers block in getConnection().";
            case "downstream-block" -> "10 HTTP workers park inside DefaultFuture.get() waiting on a downstream call.";
            default -> "unknown scenario";
        };
    }

    public static Incident start(String scenario) {
        Incident incident = new Incident();
        switch (scenario) {
            case "deadlock" -> deadlock(incident);
            case "cpu-spin" -> cpuSpin(incident);
            case "thread-leak" -> threadLeak(incident);
            case "lock-contention" -> lockContention(incident);
            case "pool-starvation" -> poolStarvation(incident);
            case "downstream-block" -> downstreamBlock(incident);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario
                    + ". Try one of " + scenarios());
        }
        return incident;
    }

    // ---------------------------------------------------------------- incidents

    private static void deadlock(Incident incident) {
        Object first = new Object();
        Object second = new Object();
        incident.thread("order-worker-1", () -> {
            synchronized (first) {
                sleep(250);
                synchronized (second) {
                    sleep(10);
                }
            }
        });
        incident.thread("order-worker-2", () -> {
            synchronized (second) {
                sleep(250);
                synchronized (first) {
                    sleep(10);
                }
            }
        });
    }

    private static void cpuSpin(Incident incident) {
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        for (int i = 0; i < workers; i++) {
            incident.thread("cpu-worker-" + i, () -> {
                while (incident.running()) {
                    cpuBurn(20_000);
                }
            });
        }
    }

    /** Deliberately allocation-free and impossible for the JIT to remove. */
    private static void cpuBurn(int rounds) {
        long value = SINK.get();
        for (int i = 0; i < rounds; i++) {
            value = value * 6364136223846793005L + 1442695040888963407L;
            value ^= (value >>> 33);
        }
        SINK.set(value);
    }

    private static void threadLeak(Incident incident) {
        CountDownLatch latch = new CountDownLatch(1);
        incident.onStop(latch::countDown);
        for (int i = 0; i < 520; i++) {
            incident.thread("order-sync-" + i, () -> {
                try {
                    latch.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private static void lockContention(Incident incident) {
        Object lock = new Object();
        incident.thread("http-nio-8080-exec-owner", () -> {
            while (incident.running()) {
                synchronized (lock) {
                    sleep(900);
                }
                sleep(60);
            }
        });
        for (int i = 0; i < 40; i++) {
            incident.thread("http-nio-8080-exec-" + (100 + i), () -> {
                while (incident.running()) {
                    synchronized (lock) {
                        sleep(5);
                    }
                    sleep(200);
                }
            });
        }
    }

    private static void poolStarvation(Incident incident) {
        Object poolLock = new Object();
        incident.thread("leaked-connection-holder", () -> {
            synchronized (poolLock) {
                while (incident.running()) {
                    sleep(5_000);
                }
            }
        });
        for (int i = 0; i < 12; i++) {
            incident.thread("http-nio-8080-exec-" + (200 + i),
                    () -> FakeHikariPool.getConnection(poolLock));
        }
    }

    private static void downstreamBlock(Incident incident) {
        DefaultFuture future = new DefaultFuture();
        for (int i = 0; i < 10; i++) {
            incident.thread("http-nio-8080-exec-" + (300 + i), () -> future.get());
        }
    }

    // ---------------------------------------------------------------- scaffolding

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Named, daemon threads plus the cleanup hooks needed to stop a scenario. */
    public static final class Incident implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Thread> threads = new ArrayList<>();
        private final List<Runnable> cleanups = new ArrayList<>();

        boolean running() {
            return running.get();
        }

        void onStop(Runnable cleanup) {
            cleanups.add(cleanup);
        }

        void thread(String name, Runnable body) {
            Thread t = new Thread(() -> {
                try {
                    body.run();
                } catch (RuntimeException ignored) {
                    // demo threads are allowed to die
                }
            }, name);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        public int threadCount() {
            return threads.size();
        }

        @Override
        public void close() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            cleanups.forEach(Runnable::run);
            threads.forEach(Thread::interrupt);
            for (Thread t : threads) {
                try {
                    t.join(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Looks like a HikariCP call site from a stack dump's point of view. */
    static final class FakeHikariPool {
        static void getConnection(Object poolLock) {
            synchronized (poolLock) {
                sleep(1_000);
            }
        }
    }

    /** Looks like a Dubbo/Netty future from a stack dump's point of view. */
    static final class DefaultFuture {
        void get() {
            LockSupport.park(this);
        }
    }
}
