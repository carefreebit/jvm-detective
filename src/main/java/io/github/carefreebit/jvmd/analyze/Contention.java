package io.github.carefreebit.jvmd.analyze;

import java.util.List;

/** Threads queued behind one monitor. */
public record Contention(String lockId, String lockClass, String ownerName, String ownerTopFrame,
                         int waiters, List<String> waiterNames) {
}
