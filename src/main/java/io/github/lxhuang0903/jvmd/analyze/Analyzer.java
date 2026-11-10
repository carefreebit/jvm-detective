package io.github.lxhuang0903.jvmd.analyze;

import io.github.lxhuang0903.jvmd.model.HeapInfo;
import io.github.lxhuang0903.jvmd.model.LockRef;
import io.github.lxhuang0903.jvmd.model.Snapshot;
import io.github.lxhuang0903.jvmd.model.StackFrame;
import io.github.lxhuang0903.jvmd.model.ThreadDump;
import io.github.lxhuang0903.jvmd.model.ThreadInfo;
import io.github.lxhuang0903.jvmd.model.VmInfo;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rule engine. It turns raw JVM evidence into a short, ranked list of findings a human can act
 * on at 3am. Every finding quotes the evidence that produced it.
 */
public final class Analyzer {

    private static final int MAX_HOT_FRAMES = 8;
    private static final int MAX_NAMES = 10;

    private static final List<String> JVM_INTERNAL = List.of(
            "C2 CompilerThread", "C1 CompilerThread", "CompilerThread", "GC Thread", "G1 ",
            "VM Thread", "VM Periodic Task", "Sweeper thread", "Service Thread",
            "Notification Thread", "Signal Dispatcher", "Attach Listener", "process reaper",
            "Reference Handler", "Finalizer", "Common-Cleaner", "DestroyJavaVM");

    public AnalysisResult analyze(Snapshot snapshot) {
        ThreadDump dump = snapshot.first();
        List<Hotspot> hotspots = computeHotspots(snapshot);
        List<Contention> contentions = computeContentions(dump);

        List<Finding> findings = new ArrayList<>();
        List<ThreadInfo> poolWaiters = connectionWaiters(dump);
        addDeadlock(snapshot, findings);
        addLockContention(contentions, poolWaiters, findings);
        addCpuHotspots(snapshot, hotspots, findings);
        addThreadLeak(snapshot, findings);
        addConnectionStarvation(poolWaiters, findings);
        addDownstreamStall(dump, findings);
        addHeapPressure(snapshot, findings);

        if (findings.isEmpty()) {
            findings.add(Finding.builder("baseline", Severity.INFO, "Baseline", "No incident signature detected")
                    .summary("No deadlock, lock pile-up, CPU hotspot, thread leak or heap pressure was found in this capture.")
                    .evidence("threads=" + dump.size(),
                            "states=" + dump.stateCounts(),
                            "heap=" + pct(snapshot.heap().usageRatio()),
                            "samples=" + snapshot.sampleCount())
                    .impact("The JVM looks healthy in this window. If users still report a problem, capture again while it is happening.")
                    .fix("Run `jvmd investigate <pid> --samples 10 --interval-ms 500 --duration 10s` during the slowdown.",
                            "Check downstream dependencies (DB, Redis, MQ) and network latency.",
                            "If the symptom is intermittent, enable JFR: `jcmd <pid> JFR.start duration=5m`.")
                    .docs("docs/playbooks/triage.md")
                    .build());
        }

        findings.sort(Comparator
                .comparingInt((Finding f) -> f.severity().rank()).reversed()
                .thenComparing(Finding::title));

        Stats stats = Stats.from(snapshot, hotspots.size(), contentions.size());
        String verdict = verdict(findings);
        String narrative = narrative(snapshot, stats, findings);
        return new AnalysisResult(findings, stats, hotspots, contentions, verdict, narrative);
    }

    // ---------------------------------------------------------------- hotspots

    private List<Hotspot> computeHotspots(Snapshot snapshot) {
        Map<String, Acc> acc = new LinkedHashMap<>();
        List<ThreadDump> samples = snapshot.samples();
        for (int i = 0; i < samples.size(); i++) {
            for (ThreadInfo t : samples.get(i).threads()) {
                if (!"RUNNABLE".equals(t.state()) || t.isIdle() || isJvmInternal(t.name()) || isOwnTool(t)) {
                    continue;
                }
                String sig = t.fingerprint(MAX_HOT_FRAMES);
                Acc a = acc.computeIfAbsent(sig, k -> new Acc(t.topFrameDisplay(), sig));
                a.samples.add(i);
                a.ids.add(t.id());
                a.names.add(t.name());
                a.cpu += Math.max(0, t.cpuMillis());
            }
        }
        int total = samples.size();
        int required = Math.max(1, (int) Math.ceil(total * 0.6));
        return acc.values().stream()
                .filter(a -> a.samples.size() >= required)
                .filter(a -> total > 1 || a.ids.size() >= 2)
                .sorted(Comparator.comparingInt((Acc a) -> a.samples.size()).reversed()
                        .thenComparing(Comparator.comparingInt((Acc a) -> a.ids.size()).reversed())
                        .thenComparing(Comparator.comparingLong((Acc a) -> a.cpu).reversed()))
                .limit(10)
                .map(a -> new Hotspot(a.sig, a.top, a.samples.size(), a.ids.size(),
                        List.copyOf(a.ids), a.cpu))
                .toList();
    }

    /** Mutable accumulator for stack signatures observed across samples. */
    private static final class Acc {
        final Set<Integer> samples = new LinkedHashSet<>();
        final Set<Long> ids = new LinkedHashSet<>();
        final Set<String> names = new LinkedHashSet<>();
        final String top;
        final String sig;
        long cpu;

        Acc(String top, String sig) {
            this.top = top;
            this.sig = sig;
        }
    }

    private void addCpuHotspots(Snapshot snapshot, List<Hotspot> hotspots, List<Finding> out) {
        if (hotspots.isEmpty()) {
            return;
        }
        Hotspot top = hotspots.get(0);
        int processors = snapshot.vm().processors() > 0 ? snapshot.vm().processors() : Runtime.getRuntime().availableProcessors();
        boolean fullWindow = top.samples() >= snapshot.sampleCount() && snapshot.sampleCount() > 1;
        boolean single = snapshot.sampleCount() == 1;

        Severity severity;
        if (single) {
            // One snapshot cannot prove a hotspot is sustained: it is a candidate, never an outage.
            severity = top.threads() >= Math.max(2, processors) ? Severity.MEDIUM : Severity.LOW;
        } else if (top.threads() >= Math.max(2, processors)) {
            severity = Severity.CRITICAL;
        } else if (fullWindow) {
            severity = Severity.HIGH;
        } else {
            severity = Severity.MEDIUM;
        }

        List<String> names = hotspots.stream().limit(3).flatMap(h -> h.threadIds().stream())
                .map(id -> threadName(snapshot.first(), id)).filter(n -> !n.isEmpty()).distinct().toList();

        List<String> evidence = new ArrayList<>();
        evidence.add(top.threads() + " thread(s) on-CPU in " + top.samples() + "/" + snapshot.sampleCount() + " samples");
        evidence.add("top frame: " + top.topFrame());
        evidence.add("CPU on those threads: " + top.cpuMillis() + " ms (cumulative)");
        if (hotspots.size() > 1) {
            evidence.add("other hot stacks: " + hotspots.get(1).topFrame()
                    + (hotspots.size() > 2 ? " | " + hotspots.get(2).topFrame() : ""));
        }
        if (single) {
            evidence.add("single snapshot: this is a candidate, not a measured hotspot");
        }

        out.add(Finding.builder("cpu-hotspot", severity, "CPU",
                        "CPU hotspot: " + simple(top.topFrame()))
                .summary(top.threads() + " thread(s) stayed runnable on the same stack"
                        + (single ? " in this snapshot." : " across " + top.samples() + " samples."))
                .evidence(evidence)
                .impact("This stack is consuming CPU cycles. At " + top.threads() + " thread(s) it can starve request processing and inflate latency.")
                .fix("Capture a profiler sample: `jcmd " + snapshot.vm().pid() + " JFR.start duration=60s filename=hot.jfr`.",
                        "Inspect the loop at " + top.topFrame() + " for unbounded work, retries or busy-waiting.",
                        "Never spin on a flag; use `LockSupport.parkNanos`, a queue, or a condition.",
                        "If this is a known batch job, isolate it in its own pool and cap concurrency.")
                .threads(names)
                .threadIds(top.threadIds())
                .docs("docs/playbooks/cpu-spin.md")
                .build());
    }

    // ---------------------------------------------------------------- deadlock

    private void addDeadlock(Snapshot snapshot, List<Finding> out) {
        ThreadDump dump = snapshot.first();
        Set<String> names = new LinkedHashSet<>(dump.deadlockedThreads());
        if (names.isEmpty()) {
            names.addAll(findCycle(dump));
        }
        if (names.isEmpty()) {
            return;
        }
        List<ThreadInfo> threads = names.stream().map(dump::byName).filter(t -> t != null).toList();
        List<String> evidence = new ArrayList<>();
        evidence.add(names.size() + " thread(s) in a lock cycle: " + String.join(", ", names));
        for (ThreadInfo t : threads) {
            if (t.waitingToLock() != null) {
                ThreadInfo owner = dump.ownersByLock().get(t.waitingToLock().id());
                evidence.add("\"" + t.name() + "\" waits for " + t.waitingToLock().label()
                        + (owner != null ? " held by \"" + owner.name() + "\"" : " (owner not in dump)"));
            }
        }
        if (threads.isEmpty()) {
            evidence.add("The JVM's own deadlock detector reported this cycle.");
        }

        out.add(Finding.builder("deadlock", Severity.CRITICAL, "Deadlock", "Threads are deadlocked")
                .summary("A lock cycle means these threads can never make progress without intervention.")
                .evidence(evidence)
                .impact("Requests handled by these threads hang forever. Pool threads are consumed until the service stops accepting work.")
                .fix("Capture evidence now: `jcmd " + snapshot.vm().pid() + " Thread.print -l > deadlock.txt` (this report already did).",
                        "Restart the instance to recover users, then reproduce in staging.",
                        "Fix lock ordering: acquire monitors in one global order, or replace nested locks with one coarse lock.",
                        "Prefer `ReentrantLock.tryLock(timeout)` so a stuck thread fails fast instead of hanging forever.")
                .threads(List.copyOf(names))
                .threadIds(threads.stream().map(ThreadInfo::id).toList())
                .docs("docs/playbooks/deadlock.md")
                .build());
    }

    /** Follows wait-for edges until a thread repeats; returns the cycle members. */
    private List<String> findCycle(ThreadDump dump) {
        Map<String, ThreadInfo> byName = new LinkedHashMap<>();
        for (ThreadInfo t : dump.threads()) {
            byName.putIfAbsent(t.name(), t);
        }
        Map<String, ThreadInfo> owners = dump.ownersByLock();
        for (ThreadInfo start : dump.threads()) {
            if (start.waitingToLock() == null) {
                continue;
            }
            List<String> path = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            ThreadInfo cursor = start;
            while (cursor != null && cursor.waitingToLock() != null) {
                if (!seen.add(cursor.name())) {
                    return path.subList(path.indexOf(cursor.name()), path.size());
                }
                path.add(cursor.name());
                LockRef lock = cursor.waitingToLock();
                cursor = owners.get(lock.id());
                if (path.size() > 64) {
                    break;
                }
            }
        }
        return List.of();
    }

    // ---------------------------------------------------------------- contention

    private List<Contention> computeContentions(ThreadDump dump) {
        Map<String, List<ThreadInfo>> waiters = dump.waitersByLock();
        Map<String, ThreadInfo> owners = dump.ownersByLock();
        List<Contention> result = new ArrayList<>();
        for (var entry : waiters.entrySet()) {
            List<ThreadInfo> list = entry.getValue();
            if (list.size() < 3) {
                continue;
            }
            ThreadInfo owner = owners.get(entry.getKey());
            result.add(new Contention(entry.getKey(), list.get(0).waitingToLock().className(),
                    owner == null ? "unknown" : owner.name(),
                    owner == null ? "not in dump" : owner.topFrameDisplay(),
                    list.size(), list.stream().map(ThreadInfo::name).limit(8).toList()));
        }
        result.sort(Comparator.comparingInt(Contention::waiters).reversed());
        return result;
    }

    private void addLockContention(List<Contention> contentions, List<ThreadInfo> poolWaiters, List<Finding> out) {
        if (contentions.isEmpty()) {
            return;
        }
        // A pool-starvation finding already explains this lock: the monitor is the pool's own
        // hand-off lock, not an independent problem. Reporting both double-counts one incident.
        if (!poolWaiters.isEmpty()) {
            var poolNames = poolWaiters.stream().map(ThreadInfo::name).collect(java.util.stream.Collectors.toSet());
            long overlap = contentions.get(0).waiterNames().stream().filter(poolNames::contains).count();
            if (overlap >= Math.min(5, poolWaiters.size())) {
                contentions = contentions.subList(1, contentions.size());
                if (contentions.isEmpty()) {
                    return;
                }
            }
        }
        Contention top = contentions.get(0);
        Severity severity = top.waiters() >= 50 ? Severity.CRITICAL
                : top.waiters() >= 10 ? Severity.HIGH : Severity.MEDIUM;

        List<String> evidence = new ArrayList<>();
        evidence.add(top.waiters() + " threads BLOCKED on <" + top.lockId() + "> (" + top.lockClass() + ")");
        evidence.add("owner: \"" + top.ownerName() + "\" at " + top.ownerTopFrame());
        evidence.add("waiting: " + String.join(", ", top.waiterNames())
                + (top.waiters() > top.waiterNames().size() ? " …" : ""));
        if (contentions.size() > 1) {
            evidence.add("other contended locks: " + (contentions.size() - 1));
        }

        out.add(Finding.builder("lock-contention", severity, "Lock contention",
                        "Lock pile-up: " + top.waiters() + " threads behind one monitor")
                .summary("One thread holds a monitor while " + top.waiters() + " others queue for it. Latency grows with the queue length.")
                .evidence(evidence)
                .impact("Throughput collapses even though CPU may look idle: the work is serialised behind one lock.")
                .fix("Shrink the critical section; never do I/O, logging or RPC while holding a lock.",
                        "Replace the global monitor with per-key locks or a concurrent data structure.",
                        "Add a timeout (`tryLock`) and a metric for lock wait time.",
                        "Inspect the owner at " + top.ownerTopFrame() + " first.")
                .threads(top.waiterNames())
                .threadIds(List.of())
                .docs("docs/playbooks/lock-contention.md")
                .build());
    }

    // ---------------------------------------------------------------- thread leak

    private void addThreadLeak(Snapshot snapshot, List<Finding> out) {
        ThreadDump first = snapshot.first();
        ThreadDump last = snapshot.last();
        int total = first.size();
        int growth = last.size() - first.size();

        List<Map.Entry<String, List<ThreadInfo>>> groups = first.threadGroups().entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<ThreadInfo>> e) -> e.getValue().size()).reversed())
                .limit(6)
                .toList();
        int largest = groups.isEmpty() ? 0 : groups.get(0).getValue().size();

        if (total < 400 && largest < 150 && growth < 40) {
            return;
        }
        Severity severity = total >= 1000 || growth >= 100 || largest >= 300 ? Severity.HIGH : Severity.MEDIUM;
        Map.Entry<String, List<ThreadInfo>> topGroup = groups.get(0);

        List<String> evidence = new ArrayList<>();
        evidence.add("total threads: " + total + " (largest group \"" + ThreadDump.ThreadNames.groupKey(topGroup.getKey())
                + "\": " + topGroup.getValue().size() + ")");
        if (snapshot.sampleCount() > 1) {
            evidence.add("growth during capture: " + (growth >= 0 ? "+" : "") + growth
                    + " threads over " + snapshot.sampleCount() + " samples");
        }
        for (var g : groups) {
            evidence.add(String.format(Locale.ROOT, "%-40s %4d threads  e.g. %s",
                    ThreadDump.ThreadNames.groupKey(g.getKey()), g.getValue().size(),
                    g.getValue().get(0).name()));
        }

        out.add(Finding.builder("thread-leak", severity, "Threads",
                        "Thread count is abnormal: " + total)
                .summary("Threads are accumulating" + (growth > 0 ? " (" + growth + " new during the capture)" : "")
                        + ". Each thread costs stack memory and scheduling overhead.")
                .evidence(evidence)
                .impact("Left unchecked this ends in `unable to create native thread` or an OOM caused by thread stacks.")
                .fix("Find the creator: `jstack <pid> | grep -A 20 \"" + topGroup.getValue().get(0).name() + "\"`.",
                        "Replace `new Thread(...)` loops with a bounded `ThreadPoolExecutor`.",
                        "Give every pool a name and a cap; log rejections.",
                        "Check for a leaked executor created per request.")
                .threads(groups.stream().flatMap(g -> g.getValue().stream()).limit(MAX_NAMES).map(ThreadInfo::name).toList())
                .docs("docs/playbooks/thread-leak.md")
                .build());
    }

    // ---------------------------------------------------------------- downstream

    private List<ThreadInfo> connectionWaiters(ThreadDump dump) {
        return dump.threads().stream()
                .filter(t -> !"RUNNABLE".equals(t.state()))
                .filter(t -> t.frames().stream().anyMatch(f -> {
                    String key = (f.className() + "." + f.methodName()).toLowerCase(Locale.ROOT);
                    return key.contains("getconnection") || key.contains("hikaripool")
                            || key.contains("datasource.getconnection");
                }))
                .toList();
    }

    private void addConnectionStarvation(List<ThreadInfo> waiting, List<Finding> out) {
        if (waiting.size() < 5) {
            return;
        }
        ThreadInfo sample = waiting.get(0);
        out.add(Finding.builder("connection-starvation", Severity.HIGH, "Downstream",
                        "Connection pool starvation: " + waiting.size() + " threads waiting")
                .summary("Threads are stacked inside `getConnection()`. The pool is empty and request threads are paying for it.")
                .evidence(waiting.size() + " threads are parked in a connection-pool call",
                        "deepest frame: " + deepestDbFrame(sample),
                        "example threads: " + String.join(", ", waiting.stream().limit(6).map(ThreadInfo::name).toList()))
                .impact("Every request that needs the database blocks until a connection is returned; latency spikes and pools cascade.")
                .fix("Check pool size and `connectionTimeout` (HikariCP: `maximumPoolSize`, `connectionTimeout`).",
                        "Find connections held too long: look for long transactions or missing `close()`.",
                        "Add a query timeout and a circuit breaker; never wait forever for a connection.",
                        "Confirm the database itself is healthy (locks, slow queries, max_connections).")
                .threads(waiting.stream().limit(MAX_NAMES).map(ThreadInfo::name).toList())
                .docs("docs/playbooks/pool-starvation.md")
                .build());
    }

    private void addDownstreamStall(ThreadDump dump, List<Finding> out) {
        record Key(String signature, String top) {
        }
        Map<Key, List<ThreadInfo>> groups = new LinkedHashMap<>();
        for (ThreadInfo t : dump.threads()) {
            if ("RUNNABLE".equals(t.state())) {
                continue;
            }
            String key = t.fingerprint(6).toLowerCase(Locale.ROOT);
            boolean downstream = key.contains("defaultfuture.get") || key.contains("future.get")
                    || key.contains("httpclient.send") || key.contains("okhttp3")
                    || key.contains("lettuce") || key.contains("jedis");
            if (!downstream) {
                continue;
            }
            StackFrame top = t.topFrame();
            if (top != null && (top.display().contains("getTask") || top.display().contains(".take("))) {
                continue;
            }
            groups.computeIfAbsent(new Key(t.fingerprint(6), t.topFrameDisplay()), k -> new ArrayList<>()).add(t);
        }
        var top = groups.entrySet().stream().max(Comparator.comparingInt(e -> e.getValue().size())).orElse(null);
        if (top == null || top.getValue().size() < 8) {
            return;
        }
        List<ThreadInfo> threads = top.getValue();
        out.add(Finding.builder("downstream-stall", Severity.MEDIUM, "Downstream",
                        threads.size() + " threads blocked on the same downstream call")
                .summary("A remote dependency is holding a large share of the request threads.")
                .evidence(threads.size() + " threads share the same stack",
                        "top frame: " + top.getKey().top(),
                        "examples: " + String.join(", ", threads.stream().limit(6).map(ThreadInfo::name).toList()))
                .impact("The local JVM looks idle while latency explodes; thread pools fill with waiters.")
                .fix("Measure the dependency's p99 latency and error rate.",
                        "Set connect/read timeouts on every client (they are often infinite by default).",
                        "Isolate the dependency with a bulkhead or circuit breaker.",
                        "Check DNS, TLS handshakes and connection-pool settings.")
                .threads(threads.stream().limit(MAX_NAMES).map(ThreadInfo::name).toList())
                .docs("docs/playbooks/downstream-stall.md")
                .build());
    }

    private String deepestDbFrame(ThreadInfo t) {
        for (StackFrame f : t.frames()) {
            String c = f.className().toLowerCase(Locale.ROOT);
            if (c.contains("mysql") || c.contains("postgres") || c.contains("hikari") || c.contains("druid")) {
                return f.display();
            }
        }
        return t.topFrameDisplay();
    }

    // ---------------------------------------------------------------- heap

    private void addHeapPressure(Snapshot snapshot, List<Finding> out) {
        HeapInfo heap = snapshot.heap();
        if (!heap.known()) {
            return;
        }
        double ratio = heap.usageRatio();
        if (ratio < 0.75) {
            return;
        }
        double metaRatio = heap.metaspaceReservedBytes() > 0
                ? (double) heap.metaspaceCommittedBytes() / heap.metaspaceReservedBytes() : -1;

        Severity severity = ratio >= 0.9 ? Severity.HIGH : Severity.MEDIUM;
        if (metaRatio >= 0.95) {
            severity = Severity.HIGH;
        }
        VmInfo vm = snapshot.vm();
        out.add(Finding.builder("heap-pressure", severity, "Memory",
                        "Heap pressure: " + pct(ratio) + " used")
                .summary("The live set is close to the maximum heap. A allocation spike can trigger a full GC storm or OOM.")
                .evidence("heap used " + mb(heap.usedBytes()) + " / " + mb(heap.maxBytes()) + " (" + pct(ratio) + ")",
                        "collector: " + heap.collector() + ", GC: " + vm.gcName() + ", Xmx: " + mb(vm.maxHeapBytes()),
                        metaRatio >= 0 ? "metaspace committed/reserved: " + pct(metaRatio) : "metaspace: n/a",
                        "threads: " + snapshot.first().size())
                .impact("GC pauses grow, throughput drops, and an OOM can kill the process while it is still serving traffic.")
                .fix("Take a heap dump before restarting: `jcmd <pid> GC.heap_dump /tmp/heap.hprof`.",
                        "Open it in Eclipse MAT and check dominator tree / retained size.",
                        "Look for unbounded caches, static collections, ThreadLocal leaks and oversized request buffers.",
                        "Only raise `-Xmx` after you know the live-set growth rate.")
                .docs("docs/playbooks/memory-pressure.md")
                .build());
    }

    // ---------------------------------------------------------------- narrative

    private String verdict(List<Finding> findings) {
        Severity worst = findings.stream().map(Finding::severity)
                .max(Comparator.comparingInt(Severity::rank)).orElse(Severity.INFO);
        return switch (worst) {
            case CRITICAL -> "CRITICAL";
            case HIGH -> "DEGRADED";
            case MEDIUM -> "WATCH";
            case LOW -> "WATCH";
            case INFO -> "HEALTHY";
        };
    }

    private String narrative(Snapshot snapshot, Stats stats, List<Finding> findings) {        Finding top = findings.get(0);
        if (top.severity() == Severity.INFO) {
            return "No smoking gun in this " + snapshot.sampleCount() + "-sample capture. "
                    + stats.totalThreads() + " threads (" + stats.runnable() + " runnable, "
                    + stats.blocked() + " blocked), heap at " + pct(stats.heapRatio()) + ". "
                    + "If the symptom is real, capture again while it is happening.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(switch (top.severity()) {
            case CRITICAL -> "The JVM is in a critical state. ";
            case HIGH -> "The JVM is degraded. ";
            default -> "There are warning signs. ";
        });
        sb.append(top.summary()).append(' ');
        if (findings.size() > 1) {
            sb.append("Next: ").append(findings.get(1).title().toLowerCase(Locale.ROOT)).append(". ");
        }
        sb.append("Evidence below is from ").append(snapshot.sampleCount())
                .append(snapshot.sampleCount() == 1 ? " snapshot" : " samples")
                .append(" taken at ").append(localTime(snapshot)).append('.');
        return sb.toString();
    }

    private static String localTime(Snapshot snapshot) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(ZoneId.systemDefault())
                .format(snapshot.capturedAt());
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isJvmInternal(String name) {
        for (String prefix : JVM_INTERNAL) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * In demo mode jvmd samples itself; its own collector threads are busy only because they are
     * taking the sample, so they must never be reported as an application hotspot.
     */
    private static boolean isOwnTool(ThreadInfo thread) {
        String name = thread.name();
        if (!name.equals("main") && !name.startsWith("jvmd-")) {
            return false;
        }
        return thread.frames().stream()
                .anyMatch(frame -> frame.className().startsWith("io.github.lxhuang0903.jvmd."));
    }

    private static String threadName(ThreadDump dump, long id) {
        return dump.threads().stream().filter(t -> t.id() == id).map(ThreadInfo::name).findFirst().orElse("");
    }

    private static String simple(String frame) {
        int paren = frame.indexOf('(');
        String head = paren > 0 ? frame.substring(0, paren) : frame;
        return head;
    }

    public static String pct(double ratio) {
        if (ratio < 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    public static String mb(long bytes) {
        if (bytes < 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.0f MB", bytes / 1024.0 / 1024.0);
    }
}
