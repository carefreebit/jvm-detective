package io.github.lxhuang0903.jvmd.analyze;

import io.github.lxhuang0903.jvmd.model.Snapshot;
import io.github.lxhuang0903.jvmd.model.ThreadDump;
import io.github.lxhuang0903.jvmd.parse.ThreadDumpParser;
import io.github.lxhuang0903.jvmd.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Analyzer")
class AnalyzerTest {

    @Test
    @DisplayName("a lock cycle is CRITICAL and names both threads")
    void detectsDeadlock() {
        AnalysisResult result = TestFixtures.analyze(TestFixtures.deadlockSnapshot());

        assertEquals("CRITICAL", result.verdict());
        assertEquals(2, result.stats().deadlocked());
        Finding finding = TestFixtures.finding(result, "deadlock");
        assertNotNull(finding);
        assertEquals(Severity.CRITICAL, finding.severity());
        assertEquals(List.of("order-worker-1", "order-worker-2"), finding.threads());
        assertTrue(finding.evidence().stream().anyMatch(e -> e.contains("lock cycle")),
                "evidence should explain the cycle: " + finding.evidence());
        assertTrue(finding.fixes().stream().anyMatch(f -> f.contains("jcmd")),
                "the first fix should capture evidence");
    }

    @Test
    @DisplayName("twelve threads behind one monitor is a HIGH lock pile-up")
    void detectsLockContention() {
        AnalysisResult result = TestFixtures.analyze(TestFixtures.contentionSnapshot());

        assertEquals("DEGRADED", result.verdict());
        Finding finding = TestFixtures.finding(result, "lock-contention");
        assertNotNull(finding);
        assertEquals(Severity.HIGH, finding.severity());
        assertEquals(8, finding.threads().size(), "the finding shows the first eight names");
        assertEquals(12, result.contentions().get(0).waiters());
        assertEquals("checkout-owner", result.contentions().get(0).ownerName());
    }

    @Test
    @DisplayName("an idle pool produces no alarm, only a baseline")
    void staysQuietOnAHealthyJvm() {
        AnalysisResult result = TestFixtures.analyze(TestFixtures.idleSnapshot());

        assertEquals("HEALTHY", result.verdict());
        assertTrue(result.findings().stream().allMatch(f -> f.severity() == Severity.INFO),
                "nothing above INFO expected: " + result.findings());
        assertNotNull(TestFixtures.finding(result, "baseline"));
    }

    @Test
    @DisplayName("a 92% heap is HIGH heap pressure")
    void detectsHeapPressure() {
        Snapshot snapshot = TestFixtures.snapshot("com.acme.web.WebApp",
                TestFixtures.threadDump("idle.txt"), 0.92);
        AnalysisResult result = TestFixtures.analyze(snapshot);

        Finding finding = TestFixtures.finding(result, "heap-pressure");
        assertNotNull(finding);
        assertEquals(Severity.HIGH, finding.severity());
    }

    @Test
    @DisplayName("the same non-idle stack in every sample is a CRITICAL CPU hotspot")
    void detectsCpuHotspotAcrossSamples() {
        AnalysisResult result = TestFixtures.analyze(cpuSpinSnapshot(4));

        assertEquals("CRITICAL", result.verdict());
        Finding finding = TestFixtures.finding(result, "cpu-hotspot");
        assertNotNull(finding);
        assertEquals(Severity.CRITICAL, finding.severity());
        assertTrue(finding.title().contains("HashIndex.rehash"), finding.title());
    }

    @Test
    @DisplayName("a single snapshot cannot promote a hotspot to CRITICAL")
    void singleSampleIsOnlyACandidate() {
        AnalysisResult result = TestFixtures.analyze(cpuSpinSnapshot(1));

        Finding finding = TestFixtures.finding(result, "cpu-hotspot");
        assertNotNull(finding);
        assertTrue(finding.severity() == Severity.MEDIUM || finding.severity() == Severity.LOW,
                "single-sample hotspot must stay a candidate, was " + finding.severity());
    }

    @Test
    @DisplayName("findings are ranked worst first")
    void ranksFindingsBySeverity() {
        AnalysisResult result = TestFixtures.analyze(cpuSpinSnapshot(4));
        for (int i = 1; i < result.findings().size(); i++) {
            Severity previous = result.findings().get(i - 1).severity();
            Severity current = result.findings().get(i).severity();
            assertTrue(previous.rank() >= current.rank(),
                    "finding " + i + " (" + current + ") outranks " + previous);
        }
    }

    @Test
    @DisplayName("an empty JVM does not crash the analyzer")
    void handlesEmptyDump() {
        ThreadDump empty = ThreadDumpParser.parse("");
        Snapshot snapshot = TestFixtures.snapshot("com.acme.Empty", empty, 0.0);
        AnalysisResult result = new Analyzer().analyze(snapshot);

        assertEquals(0, result.stats().totalThreads());
        assertNull(TestFixtures.finding(result, "deadlock"));
    }

    /** Builds {@code samples} identical dumps with ten busy threads on one stack. */
    private static Snapshot cpuSpinSnapshot(int samples) {
        String text = dumpWithBusyWorkers(10);
        List<ThreadDump> dumps = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            dumps.add(ThreadDumpParser.parse(text));
        }
        return new Snapshot(TestFixtures.vm(4242, "com.acme.hot.SearchService"),
                TestFixtures.heap(0.3), dumps, Instant.parse("2026-11-10T14:41:51Z"), Map.of());
    }

    private static String dumpWithBusyWorkers(int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("4242:\n2026-11-10 22:41:51\n")
                .append("Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.6+9-LTS-2320 mixed mode, sharing):\n\n");
        for (int i = 0; i < count; i++) {
            sb.append("\"cpu-worker-").append(i).append("\" #").append(20 + i)
                    .append(" [").append(3000 + i).append("] prio=5 os_prio=0 cpu=1000.00ms elapsed=10.00s tid=0x")
                    .append(Integer.toHexString(0x1000 + i)).append(" nid=").append(3000 + i)
                    .append(" runnable  [0x2]\n")
                    .append("   java.lang.Thread.State: RUNNABLE\n")
                    .append("    at com.acme.hot.HashIndex.rehash(HashIndex.java:88)\n")
                    .append("    at com.acme.hot.Ledger.rebuild(Ledger.java:41)\n")
                    .append("    at java.lang.Thread.run(java.base@21.0.6/Thread.java:1583)\n\n")
                    .append("   Locked ownable synchronizers:\n    - None\n\n");
        }
        return sb.toString();
    }
}
