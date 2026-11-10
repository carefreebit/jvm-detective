package io.github.carefreebit.jvmd.analyze;

import java.util.List;

/** A stack signature that stayed on-CPU across the sampling window. */
public record Hotspot(String signature, String topFrame, int samples, int threads,
                      List<Long> threadIds, long cpuMillis) {
}
