package io.github.carefreebit.jvmd.analyze;

import io.github.carefreebit.jvmd.model.HeapInfo;
import io.github.carefreebit.jvmd.model.Snapshot;
import io.github.carefreebit.jvmd.model.ThreadDump;

/** Headline numbers shown on the report dashboard. */
public record Stats(int totalThreads, int runnable, int blocked, int waiting, int timedWaiting,
                    int deadlocked, long heapUsed, long heapMax, double heapRatio,
                    int hotspots, int contendedLocks, int threadGroups, int samples) {

    public static Stats from(Snapshot snapshot, int hotspots, int contendedLocks) {
        ThreadDump dump = snapshot.first();
        HeapInfo heap = snapshot.heap();
        int runnable = 0;
        int blocked = 0;
        int waiting = 0;
        int timed = 0;
        for (var t : dump.threads()) {
            switch (t.state()) {
                case "RUNNABLE" -> runnable++;
                case "BLOCKED" -> blocked++;
                case "WAITING" -> waiting++;
                case "TIMED_WAITING" -> timed++;
                default -> {
                }
            }
        }
        return new Stats(dump.size(), runnable, blocked, waiting, timed,
                dump.deadlockedThreads().size(), heap.usedBytes(), heap.maxBytes(), heap.usageRatio(),
                hotspots, contendedLocks, dump.threadGroups().size(), snapshot.sampleCount());
    }

    public int busyThreads() {
        return runnable + blocked;
    }
}
