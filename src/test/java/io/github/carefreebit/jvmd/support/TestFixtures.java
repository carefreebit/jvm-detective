package io.github.carefreebit.jvmd.support;

import io.github.carefreebit.jvmd.analyze.AnalysisResult;
import io.github.carefreebit.jvmd.analyze.Analyzer;
import io.github.carefreebit.jvmd.analyze.Finding;
import io.github.carefreebit.jvmd.model.HeapInfo;
import io.github.carefreebit.jvmd.model.Snapshot;
import io.github.carefreebit.jvmd.model.ThreadDump;
import io.github.carefreebit.jvmd.model.VmInfo;
import io.github.carefreebit.jvmd.parse.ThreadDumpParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deterministic fixtures shared by the JVM Detective tests.
 *
 * <p>Every snapshot is built from a real capture on disk plus synthetic metadata, so the suite
 * never attaches to a JVM, sleeps or touches the network.
 */
public final class TestFixtures {

    /** Fixed capture time: keeps report folder names and rendered timestamps stable. */
    public static final Instant CAPTURED_AT = Instant.parse("2026-11-10T14:41:51Z");

    private static final long MAX_HEAP = 2L * 1024 * 1024 * 1024;

    private TestFixtures() {
    }

    /** Reads a UTF-8 classpath resource such as {@code /dumps/deadlock.txt}. */
    public static String resource(String path) {
        try (InputStream in = TestFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("missing classpath resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read classpath resource " + path, e);
        }
    }

    public static ThreadDump threadDump(String fileName) {
        return ThreadDumpParser.parse(resource("/dumps/" + fileName));
    }

    public static VmInfo vm(long pid, String mainClass) {
        return new VmInfo(pid, mainClass,
                "/usr/lib/jvm/jdk-21/bin/java -Xmx2g -XX:+UseG1GC -jar app.jar " + mainClass,
                "21.0.6", "OpenJDK 64-Bit Server VM", "Linux", "amd64",
                8_440L, 8,
                Map.of("UseG1GC", "true", "MaxHeapSize", "2147483648", "Xmx", "2g"),
                Map.of("java.version", "21.0.6",
                        "java.vm.name", "OpenJDK 64-Bit Server VM",
                        "os.name", "Linux",
                        "sun.cpu.count", "8"));
    }

    /** Heap with a 2 GiB max; {@code usedRatio} of it is live. */
    public static HeapInfo heap(double usedRatio) {
        long used = Math.round(MAX_HEAP * usedRatio);
        return new HeapInfo("garbage-first heap", used, MAX_HEAP, MAX_HEAP,
                4L * 1024 * 1024, 8L * 1024 * 1024, 1024L * 1024 * 1024);
    }

    public static Snapshot snapshot(String mainClass, ThreadDump dump, double heapRatio) {
        return new Snapshot(vm(4242, mainClass), heap(heapRatio), List.of(dump), CAPTURED_AT, Map.of());
    }

    public static Snapshot deadlockSnapshot() {
        return snapshot("com.acme.orders.OrderService", threadDump("deadlock.txt"), 0.25);
    }

    public static Snapshot contentionSnapshot() {
        return snapshot("com.acme.checkout.CheckoutService", threadDump("lock-contention.txt"), 0.42);
    }

    public static Snapshot idleSnapshot() {
        return snapshot("com.acme.web.WebApp", threadDump("idle.txt"), 0.15);
    }

    public static AnalysisResult analyze(Snapshot snapshot) {
        return new Analyzer().analyze(snapshot);
    }

    public static Finding finding(AnalysisResult result, String id) {
        return result.findings().stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
    }
}
