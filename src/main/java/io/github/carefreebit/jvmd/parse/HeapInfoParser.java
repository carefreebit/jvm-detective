package io.github.carefreebit.jvmd.parse;

import io.github.carefreebit.jvmd.model.HeapInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code jcmd GC.heap_info} for G1, Parallel, Serial, ZGC and Shenandoah. */
public final class HeapInfoParser {

    private static final Pattern HEAP = Pattern.compile("(?m)^\\s*([A-Za-z][\\w -]*?)\\s+total\\s+(\\d+)K,\\s+used\\s+(\\d+)K");
    private static final Pattern METASPACE = Pattern.compile("(?m)^\\s*Metaspace\\s+used\\s+(\\d+)K,\\s+committed\\s+(\\d+)K,\\s+reserved\\s+(\\d+)K");

    private HeapInfoParser() {
    }

    public static HeapInfo parse(String text, long maxHeapBytes) {
        if (text == null || text.isBlank()) {
            return HeapInfo.UNKNOWN;
        }
        Matcher heap = HEAP.matcher(text);
        if (!heap.find()) {
            return HeapInfo.UNKNOWN;
        }
        String collector = heap.group(1).trim();
        long total = Long.parseLong(heap.group(2)) * 1024L;
        long used = Long.parseLong(heap.group(3)) * 1024L;
        long max = maxHeapBytes > 0 ? maxHeapBytes : total;

        long msUsed = -1;
        long msCommitted = -1;
        long msReserved = -1;
        Matcher ms = METASPACE.matcher(text);
        if (ms.find()) {
            msUsed = Long.parseLong(ms.group(1)) * 1024L;
            msCommitted = Long.parseLong(ms.group(2)) * 1024L;
            msReserved = Long.parseLong(ms.group(3)) * 1024L;
        }
        return new HeapInfo(collector, used, total, max, msUsed, msCommitted, msReserved);
    }
}
