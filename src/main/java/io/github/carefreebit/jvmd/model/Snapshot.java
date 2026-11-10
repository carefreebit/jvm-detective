package io.github.carefreebit.jvmd.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything collected during one investigation: VM facts, one heap snapshot, and one or more
 * thread dumps sampled over time.
 */
public record Snapshot(VmInfo vm, HeapInfo heap, List<ThreadDump> samples, Instant capturedAt,
                       Map<String, String> raw) {

    public Snapshot {
        samples = List.copyOf(samples);
        raw = raw == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    public ThreadDump first() {
        return samples.get(0);
    }

    public ThreadDump last() {
        return samples.get(samples.size() - 1);
    }

    public int sampleCount() {
        return samples.size();
    }
}
