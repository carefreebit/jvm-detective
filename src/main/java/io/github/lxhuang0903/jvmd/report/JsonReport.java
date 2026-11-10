package io.github.lxhuang0903.jvmd.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.lxhuang0903.jvmd.analyze.AnalysisResult;
import io.github.lxhuang0903.jvmd.analyze.Contention;
import io.github.lxhuang0903.jvmd.analyze.Finding;
import io.github.lxhuang0903.jvmd.analyze.Hotspot;
import io.github.lxhuang0903.jvmd.model.Snapshot;
import io.github.lxhuang0903.jvmd.model.ThreadDump;
import io.github.lxhuang0903.jvmd.util.BuildInfo;
import io.github.lxhuang0903.jvmd.util.Risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable, machine-readable JSON for dashboards and CI gates. */
public final class JsonReport {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private JsonReport() {
    }

    public static String render(Snapshot snapshot, AnalysisResult result) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", Map.of("name", BuildInfo.NAME, "version", BuildInfo.VERSION, "repo", BuildInfo.REPO));
        root.put("verdict", result.verdict());
        root.put("riskScore", riskScore(result));
        root.put("capturedAt", snapshot.capturedAt().toString());
        root.put("samples", snapshot.sampleCount());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("pid", snapshot.vm().pid());
        target.put("mainClass", snapshot.vm().mainClass());
        target.put("javaVersion", snapshot.vm().javaVersion());
        target.put("vmName", snapshot.vm().vmName());
        target.put("gc", snapshot.vm().gcName());
        target.put("uptimeMs", snapshot.vm().uptimeMs());
        target.put("processors", snapshot.vm().processors());
        target.put("xmxBytes", snapshot.vm().maxHeapBytes());
        root.put("target", target);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("threads", result.stats().totalThreads());
        stats.put("runnable", result.stats().runnable());
        stats.put("blocked", result.stats().blocked());
        stats.put("waiting", result.stats().waiting());
        stats.put("timedWaiting", result.stats().timedWaiting());
        stats.put("deadlocked", result.stats().deadlocked());
        stats.put("heapUsedBytes", result.stats().heapUsed());
        stats.put("heapMaxBytes", result.stats().heapMax());
        stats.put("heapRatio", round(result.stats().heapRatio()));
        stats.put("threadGroups", result.stats().threadGroups());
        root.put("stats", stats);

        List<Map<String, Object>> findings = new ArrayList<>();
        for (Finding f : result.findings()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", f.id());
            item.put("severity", f.severity().name());
            item.put("category", f.category());
            item.put("title", f.title());
            item.put("summary", f.summary());
            item.put("evidence", f.evidence());
            item.put("impact", f.impact());
            item.put("fixes", f.fixes());
            item.put("threads", f.threads());
            item.put("docs", f.docs());
            findings.add(item);
        }
        root.put("findings", findings);

        List<Map<String, Object>> hotspots = new ArrayList<>();
        for (Hotspot h : result.hotspots()) {
            hotspots.add(Map.of("topFrame", h.topFrame(), "threads", h.threads(),
                    "samples", h.samples(), "cpuMillis", h.cpuMillis()));
        }
        root.put("hotspots", hotspots);

        List<Map<String, Object>> contentions = new ArrayList<>();
        for (Contention c : result.contentions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("lockId", c.lockId());
            item.put("lockClass", c.lockClass());
            item.put("owner", c.ownerName());
            item.put("ownerFrame", c.ownerTopFrame());
            item.put("waiters", c.waiters());
            item.put("waiterNames", c.waiterNames());
            contentions.add(item);
        }
        root.put("contentions", contentions);

        ThreadDump dump = snapshot.first();
        root.put("threadStates", dump.stateCounts());
        root.put("narrative", result.narrative());

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise report", e);
        }
    }

    private static int riskScore(AnalysisResult r) {
        return Risk.score(r);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
