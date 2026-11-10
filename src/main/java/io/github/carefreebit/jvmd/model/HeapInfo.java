package io.github.carefreebit.jvmd.model;

/** Heap numbers parsed from {@code jcmd GC.heap_info}. Unknown values are {@code -1}. */
public record HeapInfo(String collector, long usedBytes, long committedBytes, long maxBytes,
                       long metaspaceUsedBytes, long metaspaceCommittedBytes, long metaspaceReservedBytes) {

    public static final HeapInfo UNKNOWN = new HeapInfo("unknown", -1, -1, -1, -1, -1, -1);

    public boolean known() {
        return usedBytes >= 0 && maxBytes > 0;
    }

    /** Used / max in [0,1], or -1 when unknown. */
    public double usageRatio() {
        if (!known()) {
            return -1;
        }
        return Math.min(1.0, (double) usedBytes / (double) maxBytes);
    }

    public double committedRatio() {
        if (maxBytes <= 0 || committedBytes < 0) {
            return -1;
        }
        return Math.min(1.0, (double) committedBytes / (double) maxBytes);
    }
}
