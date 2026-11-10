package io.github.carefreebit.jvmd.parse;

import io.github.carefreebit.jvmd.model.LockRef;
import io.github.carefreebit.jvmd.model.StackFrame;
import io.github.carefreebit.jvmd.model.ThreadDump;
import io.github.carefreebit.jvmd.model.ThreadInfo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses HotSpot {@code jstack} / {@code jcmd Thread.print -l} output.
 *
 * <p>Handles both Java threads ({@code "main" #1 prio=...}) and VM-internal threads
 * ({@code "VM Thread" os_prio=...}), lock annotations, and the explicit deadlock section.
 */
public final class ThreadDumpParser {

    private static final Pattern HEADER_JAVA = Pattern.compile(
            "^\"(.*)\" #(\\d+)(?: \\[\\d+\\])? (daemon )?prio=(-?\\d+) os_prio=(-?\\d+)(?: cpu=([\\d.]+)ms)?"
                    + " elapsed=([\\d.]+)s tid=(\\S+) nid=(\\S+)(?: (.*))?$");

    private static final Pattern HEADER_VM = Pattern.compile(
            "^\"(.*)\" os_prio=(-?\\d+)(?: cpu=([\\d.]+)ms)? elapsed=([\\d.]+)s"
                    + " tid=(\\S+) nid=(\\S+)(?: (.*))?$");

    private static final Pattern JAVA_STATE = Pattern.compile("^\\s*java\\.lang\\.Thread\\.State: (\\w+)");
    private static final Pattern LOCK = Pattern.compile(
            "^\\s*- (waiting to lock|waiting to re-lock in wait\\(\\)|locked|waiting on|parking to wait for|eliminated)"
                    + " <(0x[0-9a-fA-F]+)> \\(a (.*)\\)\\s*$");
    private static final Pattern DEADLOCK_NAME = Pattern.compile("^\"(.*)\":\\s*$");
    private static final Pattern DEADLOCK_END = Pattern.compile("^Found \\d+ deadlock.*", Pattern.CASE_INSENSITIVE);

    private ThreadDumpParser() {
    }

    public static ThreadDump parse(String text) {
        List<ThreadInfo> threads = new ArrayList<>();
        List<String> deadlocked = new ArrayList<>();
        Instant timestamp = parseTimestamp(text);
        ThreadInfo current = null;
        boolean inDeadlock = false;
        boolean sawFullDump = false;

        String[] lines = text.split("\\R", -1);
        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.contains("Found one Java-level deadlock")) {
                inDeadlock = true;
                continue;
            }
            if (inDeadlock) {
                if (trimmed.startsWith("Java stack information")) {
                    // The stack section repeats the same thread names; stop here to avoid duplicates.
                    inDeadlock = false;
                    continue;
                }
                if (DEADLOCK_END.matcher(trimmed).matches()) {
                    inDeadlock = false;
                    continue;
                }
                Matcher dm = DEADLOCK_NAME.matcher(line);
                if (dm.matches()) {
                    deadlocked.add(dm.group(1));
                }
                continue;
            }

            if (trimmed.startsWith("Full thread dump")) {
                sawFullDump = true;
                continue;
            }
            if (trimmed.startsWith("JNI global refs") || trimmed.startsWith("Heap") && !sawFullDump) {
                current = null;
                continue;
            }

            Matcher jm = HEADER_JAVA.matcher(line);
            if (jm.matches()) {
                if (current != null) {
                    threads.add(current);
                }
                current = buildJavaThread(jm);
                continue;
            }
            Matcher vm = HEADER_VM.matcher(line);
            if (vm.matches()) {
                if (current != null) {
                    threads.add(current);
                }
                current = buildVmThread(vm);
                continue;
            }
            if (current == null) {
                continue;
            }

            Matcher sm = JAVA_STATE.matcher(line);
            if (sm.find()) {
                current.setState(sm.group(1));
                continue;
            }
            if (trimmed.startsWith("at ")) {
                current.addFrame(StackFrame.parse(trimmed));
                continue;
            }
            Matcher lm = LOCK.matcher(line);
            if (lm.matches()) {
                String kind = lm.group(1);
                LockRef ref = new LockRef(lm.group(2), lm.group(3));
                switch (kind) {
                    case "waiting to lock", "waiting to re-lock in wait()" -> current.setWaitingToLock(ref);
                    case "locked" -> current.addLocked(ref);
                    case "waiting on" -> current.setWaitingOn(ref);
                    case "parking to wait for" -> current.setParking(ref);
                    default -> {
                        // eliminated locks carry no contention information
                    }
                }
            }
        }
        if (current != null) {
            threads.add(current);
        }
        // VM-internal threads (GC, compiler, VM Thread) are not application threads and would
        // distort every statistic; they stay in the raw dump only.
        threads.removeIf(t -> t.id() < 0);
        List<String> uniqueDeadlocked = new ArrayList<>(new LinkedHashSet<>(deadlocked));
        return new ThreadDump(timestamp, threads, uniqueDeadlocked, text);
    }

    private static ThreadInfo buildJavaThread(Matcher m) {
        String name = m.group(1);
        long id = Long.parseLong(m.group(2));
        boolean daemon = m.group(3) != null;
        int prio = Integer.parseInt(m.group(4));
        long cpu = m.group(6) == null ? -1 : Math.round(Double.parseDouble(m.group(6)));
        double elapsed = Double.parseDouble(m.group(7));
        String tid = m.group(8);
        String nid = m.group(9);
        String osState = cleanOsState(m.group(10));
        return new ThreadInfo(name, id, daemon, prio, osState, cpu, elapsed, tid, nid, null);
    }

    private static ThreadInfo buildVmThread(Matcher m) {
        String name = m.group(1);
        long cpu = m.group(3) == null ? -1 : Math.round(Double.parseDouble(m.group(3)));
        double elapsed = Double.parseDouble(m.group(4));
        String osState = cleanOsState(m.group(7));
        return new ThreadInfo(name, -1, true, 0, osState, cpu, elapsed, m.group(5), m.group(6), null);
    }

    private static String cleanOsState(String rest) {
        if (rest == null) {
            return "";
        }
        String s = rest.trim();
        int bracket = s.lastIndexOf(" [0x");
        if (bracket > 0) {
            s = s.substring(0, bracket).trim();
        }
        return s;
    }

    static Instant parseTimestamp(String text) {
        String first = text.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.matches("\\d+:"))
                .findFirst().orElse("");
        if (first.isEmpty()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(first, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(first).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(first, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return Instant.now();
        }
    }
}
