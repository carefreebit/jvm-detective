package io.github.carefreebit.jvmd.report;

import io.github.carefreebit.jvmd.analyze.AnalysisResult;
import io.github.carefreebit.jvmd.analyze.Contention;
import io.github.carefreebit.jvmd.analyze.Finding;
import io.github.carefreebit.jvmd.analyze.Hotspot;
import io.github.carefreebit.jvmd.analyze.Severity;
import io.github.carefreebit.jvmd.analyze.Stats;
import io.github.carefreebit.jvmd.model.HeapInfo;
import io.github.carefreebit.jvmd.model.LockRef;
import io.github.carefreebit.jvmd.model.Snapshot;
import io.github.carefreebit.jvmd.model.StackFrame;
import io.github.carefreebit.jvmd.model.ThreadDump;
import io.github.carefreebit.jvmd.model.ThreadInfo;
import io.github.carefreebit.jvmd.model.VmInfo;
import io.github.carefreebit.jvmd.util.BuildInfo;
import io.github.carefreebit.jvmd.util.Format;
import io.github.carefreebit.jvmd.util.Risk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders a single, fully self-contained HTML incident report. */
public final class HtmlReport {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final int MAX_THREAD_ROWS = 1200;
    private static final String[] STATE_ORDER = {"RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "UNKNOWN"};

    private HtmlReport() {
    }

    public static String render(Snapshot snapshot, AnalysisResult result) {
        Severity worst = result.findings().isEmpty() ? Severity.INFO : result.findings().get(0).severity();
        String accent = worst == Severity.INFO ? "#3ddc97" : worst.color();
        int risk = riskScore(result);
        String caseId = caseId(snapshot);

        StringBuilder b = new StringBuilder(96_000);
        b.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>JVM Detective · CASE #").append(caseId).append(" · ").append(esc(result.verdict()))
                .append("</title>\n<style>\n").append(resource("/report/report.css")).append("\n</style>\n</head>\n<body>\n");

        b.append("<header class=\"topbar\"><div class=\"wrap\">\n")
                .append("<div class=\"brand\">").append(logo())
                .append("<span>JVM<span class=\"slash\">/</span>DETECTIVE</span>")
                .append("<span class=\"sub\">INCIDENT REPORT</span></div>\n")
                .append("<div class=\"meta\"><span>CASE #").append(caseId).append("</span>")
                .append("<span class=\"dot\"></span><span>PID ").append(snapshot.vm().pid()).append("</span>")
                .append("<span class=\"dot hide-sm\"></span><span class=\"hide-sm\">")
                .append(TIME.format(snapshot.capturedAt().atZone(ZoneId.systemDefault()))).append("</span></div>\n")
                .append("</div></header>\n<main class=\"wrap\">\n");

        renderHero(b, snapshot, result, worst, accent, risk);
        renderMetrics(b, snapshot, result);
        renderFacts(b, snapshot);
        renderFindings(b, result);
        renderAnatomy(b, snapshot, result);
        renderLocks(b, snapshot, result);
        renderThreads(b, snapshot);
        renderFooter(b, snapshot, result);

        b.append("</main>\n<div id=\"toast\">COPIED</div>\n")
                .append("<textarea id=\"ai-prompt\" class=\"hidden\">").append(esc(aiPrompt(snapshot, result))).append("</textarea>\n")
                .append("<textarea id=\"repro-command\" class=\"hidden\">").append(esc(reproCommand(snapshot))).append("</textarea>\n")
                .append("<script>\n").append(resource("/report/report.js")).append("\n</script>\n")
                .append("</body>\n</html>\n");
        return b.toString();
    }

    private static void renderHero(StringBuilder b, Snapshot s, AnalysisResult r, Severity worst,
                                   String accent, int risk) {
        String verdictClass = worst.name().toLowerCase(Locale.ROOT);
        b.append("<section class=\"hero\" style=\"--verdict:").append(accent).append("\">\n")
                .append("<div>\n<div class=\"eyebrow\"><span class=\"pulse\"></span>INCIDENT VERDICT</div>\n")
                .append("<h1 class=\"verdict ").append(verdictClass).append("\">").append(esc(r.verdict())).append("</h1>\n")
                .append("<p class=\"narrative\">").append(esc(r.narrative())).append("</p>\n")
                .append("<div class=\"hero-actions\">")
                .append("<a class=\"btn primary\" href=\"#findings\">SEE THE EVIDENCE</a>")
                .append("<button class=\"btn\" onclick=\"copyPrompt()\">COPY AI PROMPT</button>")
                .append("<button class=\"btn\" onclick=\"copyCommand()\">COPY COMMAND</button>")
                .append("</div>\n</div>\n")
                .append("<div class=\"risk\"><div class=\"risk-label\">RISK SCORE <b>").append(risk).append("/100</b></div>")
                .append("<div class=\"gauge\" style=\"--score:").append(risk).append(";--gcolor:").append(riskColor(risk)).append("\">")
                .append("<div class=\"inner\"><b>").append(risk).append("</b><span>").append(riskLabel(risk)).append("</span></div></div>")
                .append("<div class=\"risk-label\">").append(esc(severityLine(r))).append("</div></div>\n")
                .append("</section>\n");
    }

    private static void renderMetrics(StringBuilder b, Snapshot s, AnalysisResult r) {
        Stats st = r.stats();
        b.append("<div class=\"metrics\">\n");
        metric(b, "THREADS", Integer.toString(st.totalThreads()),
                st.runnable() + " runnable · " + st.blocked() + " blocked", "");
        metric(b, "DEADLOCKED", Integer.toString(st.deadlocked()),
                st.deadlocked() > 0 ? "lock cycle detected" : "none", st.deadlocked() > 0 ? "critical" : "");
        metric(b, "HEAP USED", Format.pct(st.heapRatio()),
                Format.mb(st.heapUsed()) + " / " + Format.mb(st.heapMax()),
                st.heapRatio() >= 0.85 ? "warn" : "");
        metric(b, "HOT STACKS", Integer.toString(st.hotspots()),
                "across " + st.samples() + (st.samples() == 1 ? " snapshot" : " samples"), "");
        metric(b, "CONTENDED LOCKS", Integer.toString(st.contendedLocks()),
                r.contentions().isEmpty() ? "no pile-up" : "monitors with 3+ waiters", "");
        b.append("</div>\n");
    }

    private static void metric(StringBuilder b, String key, String value, String sub, String cls) {
        b.append("<div class=\"metric\"><div class=\"k\">").append(key).append("</div>")
                .append("<div class=\"v ").append(cls).append("\">").append(esc(value)).append("</div>")
                .append("<div class=\"s\">").append(esc(sub)).append("</div></div>\n");
    }

    private static void renderFacts(StringBuilder b, Snapshot s) {
        VmInfo vm = s.vm();
        HeapInfo heap = s.heap();
        b.append("<div class=\"facts\">")
                .append("<span>JAVA <b>").append(esc(vm.javaVersion())).append("</b></span>")
                .append("<span>VM <b>").append(esc(vm.vmName())).append("</b></span>")
                .append("<span>GC <b>").append(esc(vm.gcName())).append("</b></span>")
                .append("<span>UPTIME <b>").append(esc(vm.uptimeDisplay())).append("</b></span>")
                .append("<span>CPUS <b>").append(vm.processors()).append("</b></span>")
                .append("<span>HEAP <b>").append(esc(heap.collector())).append("</b></span>")
                .append("<span>MAIN <b>").append(esc(vm.displayName())).append("</b></span>")
                .append("</div>\n");
    }

    private static void renderFindings(StringBuilder b, AnalysisResult r) {
        b.append("<section id=\"findings\"><div class=\"section-head\"><h2>Findings</h2>")
                .append("<span class=\"count\">").append(String.format(Locale.ROOT, "%02d", r.findings().size()))
                .append("</span></div>\n");

        Finding first = r.findings().get(0);
        if (first.severity() != Severity.INFO && !first.fixes().isEmpty()) {
            b.append("<div class=\"callout\"><div class=\"tag\">DO THIS FIRST</div><ol>");
            first.fixes().stream().limit(3).forEach(fix -> b.append("<li>").append(esc(fix)).append("</li>"));
            b.append("</ol></div>\n");
        }

        b.append("<div class=\"findings\">\n");
        int index = 1;
        for (Finding f : r.findings()) {
            renderFinding(b, f, index++);
        }
        b.append("</div></section>\n");
    }

    private static void renderFinding(StringBuilder b, Finding f, int index) {
        b.append("<article class=\"finding\" style=\"--sev:").append(f.severity().color()).append("\">\n")
                .append("<div class=\"finding-top\"><span class=\"sev\">").append(f.severity().label().toUpperCase(Locale.ROOT))
                .append("</span><span class=\"cat\">").append(esc(f.category().toUpperCase(Locale.ROOT)))
                .append("</span><span class=\"idx\">").append(String.format(Locale.ROOT, "%02d", index)).append("</span></div>\n")
                .append("<h3>").append(esc(f.title())).append("</h3>\n")
                .append("<p class=\"summary\">").append(esc(f.summary())).append("</p>\n");

        if (!f.evidence().isEmpty()) {
            b.append("<div class=\"evidence\"><span class=\"label\">EVIDENCE</span><pre>")
                    .append(esc(String.join("\n", f.evidence()))).append("</pre></div>\n");
        }
        if (!f.impact().isBlank()) {
            b.append("<div class=\"impact\"><span class=\"label\">WHY IT MATTERS</span><p>")
                    .append(esc(f.impact())).append("</p></div>\n");
        }
        if (!f.fixes().isEmpty()) {
            b.append("<div class=\"fix\"><span class=\"label\">FIX</span><ol>");
            for (String fix : f.fixes()) {
                b.append("<li>").append(esc(fix)).append("</li>");
            }
            b.append("</ol></div>\n");
        }
        if (!f.threads().isEmpty()) {
            b.append("<div class=\"chips\">");
            f.threads().stream().limit(12).forEach(t -> b.append("<span class=\"chip\">").append(esc(t)).append("</span>"));
            if (f.threads().size() > 12) {
                b.append("<span class=\"chip\">+").append(f.threads().size() - 12).append(" more</span>");
            }
            b.append("</div>\n");
        }
        if (f.docs() != null && !f.docs().isBlank()) {
            b.append("<a class=\"doclink\" href=\"").append(BuildInfo.DOCS).append(esc(f.docs()))
                    .append("\" target=\"_blank\" rel=\"noopener\">PLAYBOOK →</a>\n");
        }
        b.append("</article>\n");
    }

    private static void renderAnatomy(StringBuilder b, Snapshot s, AnalysisResult r) {
        Stats st = r.stats();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String state : STATE_ORDER) {
            int n = (int) s.first().count(state);
            if (n > 0) {
                counts.put(state, n);
            }
        }
        b.append("<section id=\"anatomy\"><div class=\"section-head\"><h2>Thread anatomy</h2>")
                .append("<span class=\"count\">").append(st.totalThreads()).append(" threads</span></div>\n")
                .append("<div class=\"grid2\">\n");

        b.append("<div class=\"panel\"><h3>States<span>at capture</span></h3><div class=\"donut-wrap\">")
                .append("<div class=\"donut\" style=\"--donut:").append(donutGradient(counts, st.totalThreads())).append("\">")
                .append("<div class=\"hole\"><b>").append(st.totalThreads()).append("</b><span>THREADS</span></div></div>")
                .append("<ul class=\"legend\">");
        for (String state : STATE_ORDER) {
            int n = counts.getOrDefault(state, 0);
            if (n == 0) {
                continue;
            }
            b.append("<li><i style=\"background:").append(stateColor(state)).append("\"></i>")
                    .append(state).append("<b>").append(n).append("</b></li>");
        }
        b.append("</ul></div></div>\n");

        b.append("<div class=\"panel\"><h3>Top on-CPU stacks<span>");
        if (s.sampleCount() > 1) {
            b.append(s.sampleCount()).append(" samples");
        } else {
            b.append("single snapshot");
        }
        b.append("</span></h3><div class=\"bars\">");
        List<Hotspot> hotspots = r.hotspots();
        if (hotspots.isEmpty()) {
            b.append("<div class=\"empty\">No stack stayed on-CPU across the window. This is normal for I/O-bound services.</div>");
        } else {
            int max = hotspots.stream().mapToInt(Hotspot::threads).max().orElse(1);
            for (Hotspot h : hotspots.stream().limit(6).toList()) {
                int width = Math.max(4, (int) Math.round(h.threads() * 100.0 / max));
                b.append("<div class=\"bar\"><div class=\"row\"><span class=\"name\">").append(esc(h.topFrame()))
                        .append("</span><span class=\"num\">").append(h.threads()).append(" thr · ")
                        .append(h.samples()).append("/").append(s.sampleCount()).append("</span></div>")
                        .append("<div class=\"track\"><div class=\"fill\" style=\"width:").append(width).append("%\"></div></div></div>");
            }
        }
        b.append("</div></div>\n</div></section>\n");
    }

    private static void renderLocks(StringBuilder b, Snapshot s, AnalysisResult r) {
        List<Contention> contentions = r.contentions();
        List<String> chains = waitChains(s.first());
        if (contentions.isEmpty() && chains.isEmpty()) {
            return;
        }
        b.append("<section id=\"locks\"><div class=\"section-head\"><h2>Lock map</h2>")
                .append("<span class=\"count\">").append(contentions.size()).append(" contended</span></div>\n")
                .append("<div class=\"grid2\">\n");

        b.append("<div class=\"panel\"><h3>Wait-for chains<span>first snapshot</span></h3>");
        if (chains.isEmpty()) {
            b.append("<div class=\"empty\">No thread is waiting for a monitor owned by another thread.</div>");
        } else {
            b.append("<div class=\"flow\">");
            for (String chain : chains) {
                b.append(chain).append("<br>");
            }
            b.append("</div>");
        }
        b.append("</div>\n");

        b.append("<div class=\"panel\"><h3>Most contended monitors<span>3+ waiters</span></h3><div class=\"locks\">");
        if (contentions.isEmpty()) {
            b.append("<div class=\"empty\">No monitor has three or more waiters.</div>");
        } else {
            for (Contention c : contentions.stream().limit(6).toList()) {
                b.append("<div class=\"lock\"><div class=\"id\">&lt;").append(esc(c.lockId())).append("&gt; ")
                        .append(esc(c.lockClass())).append("</div><div class=\"n\">").append(c.waiters())
                        .append(" waiters</div><div class=\"who\">held by \"").append(esc(c.ownerName()))
                        .append("\" @ ").append(esc(c.ownerTopFrame())).append("</div></div>");
            }
        }
        b.append("</div></div>\n</div></section>\n");
    }

    private static List<String> waitChains(ThreadDump dump) {
        List<String> chains = new ArrayList<>();
        Map<String, ThreadInfo> owners = dump.ownersByLock();
        for (ThreadInfo t : dump.threads()) {
            if (t.waitingToLock() == null || chains.size() >= 8) {
                continue;
            }
            ThreadInfo owner = owners.get(t.waitingToLock().id());
            if (owner == null) {
                continue;
            }
            chains.add("<span class=\"t\">\"" + esc(t.name()) + "\"</span><span class=\"arrow\">→</span>"
                    + "<span class=\"l\">&lt;" + esc(t.waitingToLock().id()) + "&gt;</span>"
                    + "<span class=\"arrow\">→</span><span class=\"t\">\"" + esc(owner.name()) + "\"</span>"
                    + "<span class=\"arrow\">at</span><span class=\"l\">" + esc(owner.topFrameDisplay()) + "</span>");
        }
        return chains;
    }

    private static void renderThreads(StringBuilder b, Snapshot s) {
        ThreadDump dump = s.first();
        b.append("<section id=\"threads\"><div class=\"section-head\"><h2>All threads</h2>")
                .append("<span class=\"count\"><span id=\"thread-visible\">").append(dump.size())
                .append("</span> / ").append(dump.size()).append("</span>")
                .append("<div class=\"filters\">")
                .append("<button class=\"filter on\" data-filter=\"ALL\">ALL</button>")
                .append("<button class=\"filter\" data-filter=\"RUNNABLE\">RUNNABLE</button>")
                .append("<button class=\"filter\" data-filter=\"BLOCKED\">BLOCKED</button>")
                .append("<button class=\"filter\" data-filter=\"WAITING\">WAITING</button>")
                .append("<button class=\"filter\" data-filter=\"TIMED_WAITING\">TIMED</button>")
                .append("<input id=\"thread-search\" type=\"search\" placeholder=\"filter by name or frame…\" autocomplete=\"off\">")
                .append("</div></div>\n");

        if (dump.size() > MAX_THREAD_ROWS) {
            b.append("<div class=\"empty\">Showing the first ").append(MAX_THREAD_ROWS).append(" of ")
                    .append(dump.size()).append(" threads. The full dump is saved next to this report.</div>\n");
        }
        b.append("<div class=\"threads\">");
        for (ThreadInfo t : dump.threads().stream().limit(MAX_THREAD_ROWS).toList()) {
            renderThread(b, t);
        }
        b.append("</div></section>\n");
    }

    private static void renderThread(StringBuilder b, ThreadInfo t) {
        String search = (t.name() + " " + t.topFrameDisplay()).toLowerCase(Locale.ROOT);
        b.append("<details class=\"thread\" data-state=\"").append(t.state()).append("\" data-search=\"")
                .append(esc(search)).append("\"><summary>")
                .append("<span class=\"state ").append(t.state()).append("\">").append(t.state()).append("</span>")
                .append("<span class=\"tname\">").append(esc(t.name())).append("</span>")
                .append("<span class=\"tframe\">").append(esc(t.topFrameDisplay())).append("</span>")
                .append("<span class=\"chev\">›</span></summary>")
                .append("<div class=\"stack\">");
        List<String> locks = new ArrayList<>();
        if (t.waitingToLock() != null) {
            locks.add("- waiting to lock " + t.waitingToLock().label());
        }
        for (LockRef ref : t.locked()) {
            locks.add("- locked " + ref.label());
        }
        if (t.waitingOn() != null) {
            locks.add("- waiting on " + t.waitingOn().label());
        }
        if (t.parking() != null) {
            locks.add("- parking to wait for " + t.parking().label());
        }
        if (!locks.isEmpty()) {
            b.append("<div class=\"locks\">");
            for (String lock : locks) {
                b.append("<div class=\"lock\"><div class=\"id\">").append(esc(lock)).append("</div></div>");
            }
            b.append("</div>");
        }
        b.append("<pre>");
        if (t.frames().isEmpty()) {
            b.append("(no Java frames)");
        } else {
            for (StackFrame frame : t.frames()) {
                b.append("    ").append(esc(frame.raw())).append('\n');
            }
        }
        b.append("</pre></div></details>\n");
    }

    private static void renderFooter(StringBuilder b, Snapshot s, AnalysisResult r) {
        b.append("<footer><div class=\"row\"><div>")
                .append("Generated by ").append(BuildInfo.NAME).append(" v").append(BuildInfo.VERSION)
                .append(" · ").append(TIME.format(s.capturedAt().atZone(ZoneId.systemDefault())))
                .append("<br>Self-contained report. No data left this machine.")
                .append("</div><div style=\"text-align:right\">")
                .append("PID ").append(s.vm().pid()).append(" · ").append(esc(s.vm().displayName()))
                .append("<br><a href=\"").append(BuildInfo.REPO).append("\" target=\"_blank\" rel=\"noopener\">")
                .append("github.com/carefreebit/jvm-detective</a></div></div></footer>\n");
    }

    // ---------------------------------------------------------------- helpers

    private static String aiPrompt(Snapshot s, AnalysisResult r) {
        StringBuilder b = new StringBuilder();
        b.append("# JVM incident report\n\n");
        b.append("Verdict: ").append(r.verdict()).append("\n");
        b.append("PID: ").append(s.vm().pid()).append("  Main: ").append(s.vm().displayName())
                .append("  Java: ").append(s.vm().javaVersion()).append("  GC: ").append(s.vm().gcName())
                .append("  Uptime: ").append(s.vm().uptimeDisplay()).append("\n");
        b.append("Threads: ").append(r.stats().totalThreads()).append(" (").append(r.stats().runnable())
                .append(" runnable, ").append(r.stats().blocked()).append(" blocked)  Heap: ")
                .append(Format.mb(r.stats().heapUsed())).append(" / ").append(Format.mb(r.stats().heapMax()))
                .append("  Samples: ").append(r.stats().samples()).append("\n\n");
        b.append("## Findings\n");
        for (Finding f : r.findings()) {
            b.append("\n### [").append(f.severity().label().toUpperCase(Locale.ROOT)).append("] ").append(f.title()).append("\n");
            b.append(f.summary()).append("\n\n");
            b.append("Evidence:\n");
            for (String e : f.evidence()) {
                b.append("- ").append(e).append("\n");
            }
            b.append("\nImpact: ").append(f.impact()).append("\n");
            b.append("Suggested fixes:\n");
            for (String fix : f.fixes()) {
                b.append("- ").append(fix).append("\n");
            }
        }
        b.append("\n## Top on-CPU stacks\n");
        for (Hotspot h : r.hotspots().stream().limit(8).toList()) {
            b.append("- ").append(h.threads()).append(" threads, ").append(h.samples()).append("/")
                    .append(r.stats().samples()).append(" samples: ").append(h.topFrame()).append("\n");
        }
        b.append("\nAct as a senior JVM performance engineer. Identify the most likely root cause, ")
                .append("rank the fixes by risk, and give exact commands or queries to confirm each hypothesis. ")
                .append("Ask for the full thread dump if you need more evidence.\n");
        return b.toString();
    }

    private static String reproCommand(Snapshot s) {
        return "jvmd investigate " + s.vm().pid() + " --samples " + s.sampleCount()
                + " --interval-ms 400";
    }

    private static String severityLine(AnalysisResult r) {
        long critical = r.count(Severity.CRITICAL);
        long high = r.count(Severity.HIGH);
        long medium = r.count(Severity.MEDIUM);
        if (critical + high + medium == 0) {
            return "NO BLOCKING SIGNAL";
        }
        return critical + " critical · " + high + " high · " + medium + " medium";
    }

    private static int riskScore(AnalysisResult r) {
        return Risk.score(r);
    }

    private static String riskColor(int risk) {
        return Risk.color(risk);
    }

    private static String riskLabel(int risk) {
        return Risk.label(risk);
    }

    private static String donutGradient(Map<String, Integer> counts, int total) {
        if (total <= 0) {
            return "conic-gradient(#1a1f2c 0 100%)";
        }
        StringBuilder b = new StringBuilder("conic-gradient(");
        double cursor = 0;
        boolean first = true;
        for (String state : STATE_ORDER) {
            int n = counts.getOrDefault(state, 0);
            if (n == 0) {
                continue;
            }
            double next = cursor + n * 100.0 / total;
            if (!first) {
                b.append(", ");
            }
            b.append(stateColor(state)).append(' ').append(String.format(Locale.ROOT, "%.2f%%", cursor))
                    .append(' ').append(String.format(Locale.ROOT, "%.2f%%", next));
            cursor = next;
            first = false;
        }
        b.append(')');
        return b.toString();
    }

    private static String stateColor(String state) {
        return switch (state) {
            case "RUNNABLE" -> "#3ddc97";
            case "BLOCKED" -> "#ff4d4f";
            case "WAITING" -> "#f5b942";
            case "TIMED_WAITING" -> "#4dabf7";
            default -> "#5d6579";
        };
    }

    public static String caseId(Snapshot s) {
        long hash = s.vm().pid() * 31L + s.capturedAt().getEpochSecond();
        return String.format(Locale.ROOT, "%06X", Math.abs(hash) % 0xFFFFFF);
    }

    private static String logo() {
        return "<svg width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\">"
                + "<circle cx=\"10.5\" cy=\"10.5\" r=\"6.3\" stroke=\"#ffb020\" stroke-width=\"1.6\"/>"
                + "<path d=\"M15.4 15.4L21 21\" stroke=\"#ffb020\" stroke-width=\"1.6\" stroke-linecap=\"round\"/>"
                + "<path d=\"M7.2 10.5h1.8l1-2.2 1.6 4.4 1-2.2h1.6\" stroke=\"#ffb020\" stroke-width=\"1.2\" "
                + "stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>";
    }

    static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String resource(String path) {
        try (InputStream in = HtmlReport.class.getResourceAsStream(path)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
