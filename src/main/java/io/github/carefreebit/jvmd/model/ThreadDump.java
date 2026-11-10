package io.github.carefreebit.jvmd.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A parsed thread dump: one point-in-time view of every thread in a JVM. */
public final class ThreadDump {

    private final Instant timestamp;
    private final List<ThreadInfo> threads;
    private final List<String> deadlockedThreads;
    private final String raw;

    public ThreadDump(Instant timestamp, List<ThreadInfo> threads, List<String> deadlockedThreads, String raw) {
        this.timestamp = timestamp;
        this.threads = List.copyOf(threads);
        this.deadlockedThreads = List.copyOf(deadlockedThreads);
        this.raw = raw;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public List<ThreadInfo> threads() {
        return threads;
    }

    public List<String> deadlockedThreads() {
        return deadlockedThreads;
    }

    public String raw() {
        return raw;
    }

    public int size() {
        return threads.size();
    }

    public Map<String, Integer> stateCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ThreadInfo t : threads) {
            counts.merge(t.state(), 1, Integer::sum);
        }
        return counts;
    }

    public long count(String state) {
        return threads.stream().filter(t -> t.state().equals(state)).count();
    }

    public List<ThreadInfo> inState(String state) {
        return threads.stream().filter(t -> t.state().equals(state)).toList();
    }

    public ThreadInfo byName(String name) {
        return threads.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    /** lock id -> threads waiting to acquire it. */
    public Map<String, List<ThreadInfo>> waitersByLock() {
        Map<String, List<ThreadInfo>> map = new LinkedHashMap<>();
        for (ThreadInfo t : threads) {
            if (t.waitingToLock() != null) {
                map.computeIfAbsent(t.waitingToLock().id(), k -> new ArrayList<>()).add(t);
            }
        }
        return map;
    }

    /** lock id -> thread that currently holds it. */
    public Map<String, ThreadInfo> ownersByLock() {
        Map<String, ThreadInfo> map = new LinkedHashMap<>();
        for (ThreadInfo t : threads) {
            for (LockRef ref : t.locked()) {
                map.putIfAbsent(ref.id(), t);
            }
        }
        return map;
    }

    /** Thread counts grouped by a normalised name prefix ("http-nio-8080-exec-42" -> "http-nio-8080-exec"). */
    public Map<String, List<ThreadInfo>> threadGroups() {
        Map<String, List<ThreadInfo>> groups = new LinkedHashMap<>();
        for (ThreadInfo t : threads) {
            groups.computeIfAbsent(ThreadNames.groupKey(t.name()), k -> new ArrayList<>()).add(t);
        }
        return groups;
    }

    public static final class ThreadNames {
        private ThreadNames() {
        }

        /** Strip trailing ids / indexes so pool threads collapse into one group. */
        public static String groupKey(String name) {
            if (name == null) {
                return "unknown";
            }
            String s = name.replaceAll("[-#]?\\d+$", "");
            s = s.replaceAll("\\s+", " ").trim();
            return s.isEmpty() ? name : s;
        }

        public static boolean looksLikePool(String name) {
            String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return n.startsWith("http-nio") || n.startsWith("http-bio") || n.startsWith("tomcat")
                    || n.startsWith("reactor-http") || n.startsWith("reactor-tcp")
                    || n.startsWith("hikaripool") || n.startsWith("dubbo")
                    || n.startsWith("pool-") || n.startsWith("exec-")
                    || n.startsWith("async-") || n.startsWith("lettuce")
                    || n.startsWith("redisson") || n.startsWith("okhttp")
                    || n.startsWith("netty") || n.startsWith("grpc")
                    || n.startsWith("kafka") || n.startsWith("rocketmq")
                    || n.startsWith("scheduler") || n.startsWith("quartz");
        }
    }
}
