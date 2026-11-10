package io.github.carefreebit.jvmd.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Identity and runtime facts about the target JVM. */
public record VmInfo(long pid, String mainClass, String commandLine, String javaVersion, String vmName,
                     String osName, String osArch, long uptimeMs, int processors,
                     Map<String, String> flags, Map<String, String> properties) {

    public VmInfo {
        flags = flags == null ? Map.of() : Map.copyOf(flags);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static VmInfo unknown(long pid) {
        return new VmInfo(pid, "unknown", "", "unknown", "unknown", System.getProperty("os.name"),
                System.getProperty("os.arch"), -1, Runtime.getRuntime().availableProcessors(),
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    public String property(String key) {
        return properties.getOrDefault(key, "");
    }

    /** Main class, or just the jar file name when the JVM was started with {@code -jar}. */
    public String displayName() {
        if (mainClass == null || mainClass.isBlank()) {
            return "unknown";
        }
        if (mainClass.endsWith(".jar") || mainClass.endsWith(".war")) {
            int slash = Math.max(mainClass.lastIndexOf('/'), mainClass.lastIndexOf('\\'));
            return slash >= 0 ? mainClass.substring(slash + 1) : mainClass;
        }
        return mainClass;
    }

    public String flag(String name) {
        return flags.get(name);
    }

    public boolean hasFlag(String name) {
        return flags.containsKey(name);
    }

    /** Best-effort -Xmx in bytes, or -1. */
    public long maxHeapBytes() {
        String max = flags.get("MaxHeapSize");
        if (max != null) {
            return parseSize(max);
        }
        String xmx = flags.get("Xmx");
        if (xmx != null) {
            return parseSize(xmx);
        }
        return -1;
    }

    public String gcName() {
        if (hasFlag("UseG1GC")) {
            return "G1";
        }
        if (hasFlag("UseZGC")) {
            return "ZGC";
        }
        if (hasFlag("UseShenandoahGC")) {
            return "Shenandoah";
        }
        if (hasFlag("UseParallelGC")) {
            return "Parallel";
        }
        if (hasFlag("UseSerialGC")) {
            return "Serial";
        }
        if (hasFlag("UseEpsilonGC")) {
            return "Epsilon";
        }
        return "default";
    }

    public String uptimeDisplay() {
        if (uptimeMs < 0) {
            return "unknown";
        }
        long seconds = uptimeMs / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    public static long parseSize(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        if (s.endsWith("k") || s.endsWith("kb")) {
            multiplier = 1024L;
            s = s.replace("kb", "").replace("k", "");
        } else if (s.endsWith("m") || s.endsWith("mb")) {
            multiplier = 1024L * 1024L;
            s = s.replace("mb", "").replace("m", "");
        } else if (s.endsWith("g") || s.endsWith("gb")) {
            multiplier = 1024L * 1024L * 1024L;
            s = s.replace("gb", "").replace("g", "");
        } else if (s.endsWith("t") || s.endsWith("tb")) {
            multiplier = 1024L * 1024L * 1024L * 1024L;
            s = s.replace("tb", "").replace("t", "");
        }
        try {
            return (long) (Double.parseDouble(s.trim()) * multiplier);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
