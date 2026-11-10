package io.github.lxhuang0903.jvmd.collect;

import io.github.lxhuang0903.jvmd.model.HeapInfo;
import io.github.lxhuang0903.jvmd.model.Snapshot;
import io.github.lxhuang0903.jvmd.model.ThreadDump;
import io.github.lxhuang0903.jvmd.model.VmInfo;
import io.github.lxhuang0903.jvmd.parse.HeapInfoParser;
import io.github.lxhuang0903.jvmd.parse.ThreadDumpParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Collects a consistent snapshot of a live JVM using nothing but {@code jcmd}. */
public final class Collector {

    private static final Pattern UPTIME = Pattern.compile("([\\d.]+)\\s*(ms|s|sec|seconds)\\b");
    private static final Pattern VM_INFO_PROCESSORS = Pattern.compile("active_processor_count\\s*=\\s*(\\d+)");

    private Collector() {
    }

    public static Snapshot collect(long pid, int samples, long intervalMs, Consumer<String> log) {
        if (samples < 1) {
            throw new IllegalArgumentException("samples must be >= 1");
        }
        if (intervalMs < 0) {
            throw new IllegalArgumentException("interval must be >= 0");
        }
        Map<String, String> raw = new LinkedHashMap<>();
        List<Jcmd.JvmProcess> processes = Jcmd.processes();
        long self = ProcessHandle.current().pid();
        Jcmd.JvmProcess target = processes.stream().filter(p -> p.pid() == pid).findFirst()
                .orElseGet(() -> {
                    if (pid == self) {
                        return new Jcmd.JvmProcess(pid, "jvmd (demo)");
                    }
                    throw new IllegalArgumentException("No JVM with pid " + pid + " found. Run `jvmd list`.");
                });
        log.accept("target  " + pid + "  " + target.mainClass());

        Jcmd.Result info = run(pid, raw, "vm.info", "VM.info");
        Jcmd.Result flags = run(pid, raw, "vm.flags", "VM.flags");
        Jcmd.Result props = run(pid, raw, "vm.properties", "VM.system_properties");
        Jcmd.Result uptime = run(pid, raw, "vm.uptime", "VM.uptime");
        Jcmd.Result heapRaw = run(pid, raw, "heap.info", "GC.heap_info");

        Map<String, String> flagMap = parseFlags(flags.stdout());
        Map<String, String> propMap = parseProperties(props.stdout());
        long uptimeMs = parseUptime(uptime.stdout());
        int processors = parseProcessors(info.stdout(), propMap);
        long maxHeap = VmInfo.parseSize(flagMap.get("MaxHeapSize"));
        if (maxHeap <= 0) {
            maxHeap = VmInfo.parseSize(flagMap.get("Xmx"));
        }
        if (maxHeap <= 0) {
            maxHeap = maxHeapFromInfo(info.stdout());
        }
        HeapInfo heap = HeapInfoParser.parse(heapRaw.stdout(), maxHeap);

        String mainClass = propMap.getOrDefault("sun.java.command", target.mainClass()).trim();
        int space = mainClass.indexOf(' ');
        if (space > 0) {
            mainClass = mainClass.substring(0, space);
        }
        VmInfo vm = new VmInfo(pid, mainClass, target.command(),
                propMap.getOrDefault("java.version", "unknown"),
                propMap.getOrDefault("java.vm.name", "unknown"),
                propMap.getOrDefault("os.name", ""),
                System.getProperty("os.arch"), uptimeMs, processors, flagMap, propMap);

        log.accept("heap    " + usedSummary(heap) + "  " + heap.collector());
        log.accept("threads sampling " + samples + " x " + intervalMs + "ms");

        List<ThreadDump> dumps = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            Jcmd.Result dump = Jcmd.run(pid, "Thread.print", "-l");
            if (!dump.ok() && dump.stdout().isBlank()) {
                throw new IllegalStateException("Thread.print failed: " + dump.errorText()
                        + "\nIf the target runs as another user, run JVM Detective as that user.");
            }
            raw.put("thread." + i, dump.stdout());
            ThreadDump parsed = ThreadDumpParser.parse(dump.stdout());
            dumps.add(parsed);
            log.accept("sample  " + (i + 1) + "/" + samples + "  threads=" + parsed.size()
                    + "  blocked=" + parsed.count("BLOCKED") + "  deadlocked=" + parsed.deadlockedThreads().size());
            if (i + 1 < samples) {
                sleep(intervalMs);
            }
        }
        return new Snapshot(vm, heap, dumps, Instant.now(), raw);
    }

    private static Jcmd.Result run(long pid, Map<String, String> raw, String key, String command) {
        Jcmd.Result result = Jcmd.run(pid, command);
        raw.put(key, result.stdout());
        if (!result.ok() && result.stdout().isBlank()) {
            throw new IllegalStateException("`jcmd " + pid + " " + command + "` failed: " + result.errorText());
        }
        return result;
    }

    static Map<String, String> parseFlags(String text) {
        Map<String, String> flags = new LinkedHashMap<>();
        if (text == null) {
            return flags;
        }
        for (String token : text.trim().split("\\s+")) {
            if (token.startsWith("-XX:+")) {
                flags.put(token.substring(5), "true");
            } else if (token.startsWith("-XX:-")) {
                flags.put(token.substring(5), "false");
            } else if (token.startsWith("-XX:")) {
                String body = token.substring(4);
                int eq = body.indexOf('=');
                if (eq > 0) {
                    flags.put(body.substring(0, eq), body.substring(eq + 1));
                }
            } else if (token.startsWith("-Xmx")) {
                flags.put("Xmx", token.substring(4));
            } else if (token.startsWith("-Xms")) {
                flags.put("Xms", token.substring(4));
            } else if (token.startsWith("-Xss")) {
                flags.put("Xss", token.substring(4));
            }
        }
        return flags;
    }

    static Map<String, String> parseProperties(String text) {
        Map<String, String> props = new LinkedHashMap<>();
        if (text == null) {
            return props;
        }
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.put(line.substring(0, eq).trim(), unescapeProperty(line.substring(eq + 1).trim()));
            }
        }
        return props;
    }

    /** jcmd prints system properties in .properties form, where {@code \\} and {@code \\:} are escapes. */
    static String unescapeProperty(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    static long parseUptime(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        Matcher m = UPTIME.matcher(text);
        long millis = -1;
        while (m.find()) {
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            millis = unit.equals("ms") ? (long) value : (long) (value * 1000);
        }
        return millis;
    }

    static int parseProcessors(String vmInfo, Map<String, String> props) {
        Matcher m = VM_INFO_PROCESSORS.matcher(vmInfo == null ? "" : vmInfo);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        String count = props.get("sun.cpu.count");
        if (count != null) {
            try {
                return Integer.parseInt(count.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return Runtime.getRuntime().availableProcessors();
    }

    static long maxHeapFromInfo(String vmInfo) {
        Matcher m = Pattern.compile("(?m)^\\s*MaxHeapSize\\s*=\\s*(\\d+)").matcher(vmInfo == null ? "" : vmInfo);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String firstGroup(Pattern pattern, String text, String fallback) {
        Matcher m = pattern.matcher(text == null ? "" : text);
        return m.find() ? m.group(1).trim() : fallback;
    }

    private static String usedSummary(HeapInfo heap) {
        if (!heap.known()) {
            return "unknown";
        }
        return Math.round(heap.usedBytes() / 1024.0 / 1024.0) + " MB / "
                + Math.round(heap.maxBytes() / 1024.0 / 1024.0) + " MB";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
