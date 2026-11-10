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
                .append("<span class=\"sub\" data-en=\"INCIDENT REPORT\" data-zh=\"事故报告\">INCIDENT REPORT</span></div>\n")
                .append("<div class=\"meta\"><span>CASE #").append(caseId).append("</span>")
                .append("<span class=\"dot\"></span><span>PID ").append(snapshot.vm().pid()).append("</span>")
                .append("<span class=\"dot hide-sm\"></span><span class=\"hide-sm\">")
                .append(TIME.format(snapshot.capturedAt().atZone(ZoneId.systemDefault()))).append("</span>")
                .append("<span class=\"lang\"><button type=\"button\" data-lang=\"en\" class=\"on\">EN</button>")
                .append("<button type=\"button\" data-lang=\"zh\">\u4E2D\u6587</button></span></div>\n")
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
                .append("<textarea id=\"ai-prompt-en\" class=\"hidden\">").append(esc(aiPrompt(snapshot, result))).append("</textarea>\n")
                .append("<textarea id=\"ai-prompt-zh\" class=\"hidden\">").append(esc(aiPromptZh(snapshot, result))).append("</textarea>\n")
                .append("<textarea id=\"repro-command\" class=\"hidden\">").append(esc(reproCommand(snapshot))).append("</textarea>\n")
                .append("<script>\n").append(resource("/report/report.js")).append("\n</script>\n")
                .append("</body>\n</html>\n");
        return b.toString();
    }

    private static void renderHero(StringBuilder b, Snapshot s, AnalysisResult r, Severity worst,
                                   String accent, int risk) {
        String verdictClass = worst.name().toLowerCase(Locale.ROOT);
        b.append("<section class=\"hero\" style=\"--verdict:").append(accent).append("\">\n")
                .append("<div>\n<div class=\"eyebrow\"><span class=\"pulse\"></span>")
                .append("<span data-en=\"INCIDENT VERDICT\" data-zh=\"事故判定\">INCIDENT VERDICT</span></div>\n")
                .append("<h1 class=\"verdict ").append(verdictClass).append("\" data-en=\"").append(esc(r.verdict()))
                .append("\" data-zh=\"").append(esc(verdictZh(r.verdict()))).append("\">")
                .append(esc(r.verdict())).append("</h1>\n")
                .append("<p class=\"narrative\" data-en=\"").append(esc(r.narrative())).append("\" data-zh=\"")
                .append(esc(r.narrativeZh())).append("\">").append(esc(r.narrative())).append("</p>\n")
                .append("<div class=\"hero-actions\">")
                .append("<a class=\"btn primary\" href=\"#findings\" data-en=\"SEE THE EVIDENCE\" data-zh=\"查看证据\">SEE THE EVIDENCE</a>")
                .append("<button class=\"btn\" onclick=\"copyPrompt()\" data-en=\"COPY AI PROMPT\" data-zh=\"复制 AI 提示\">COPY AI PROMPT</button>")
                .append("<button class=\"btn\" onclick=\"copyCommand()\" data-en=\"COPY COMMAND\" data-zh=\"复制命令\">COPY COMMAND</button>")
                .append("</div>\n</div>\n")
                .append("<div class=\"risk\"><div class=\"risk-label\"><span data-en=\"RISK SCORE\" data-zh=\"风险评分\">RISK SCORE</span> <b>")
                .append(risk).append("/100</b></div>")
                .append("<div class=\"gauge\" style=\"--score:").append(risk).append(";--gcolor:").append(riskColor(risk)).append("\">")
                .append("<div class=\"inner\"><b>").append(risk).append("</b><span>").append(riskLabel(risk)).append("</span></div></div>")
                .append("<div class=\"risk-label\" data-en=\"").append(esc(severityLine(r))).append("\" data-zh=\"")
                .append(esc(severityLineZh(r))).append("\">").append(esc(severityLine(r))).append("</div></div>\n")
                .append("</section>\n");
    }

    private static void renderMetrics(StringBuilder b, Snapshot s, AnalysisResult r) {
        Stats st = r.stats();
        b.append("<div class=\"metrics\">\n");
        metric(b, "THREADS", "\u7EBF\u7A0B\u6570", Integer.toString(st.totalThreads()),
                st.runnable() + " runnable \u00B7 " + st.blocked() + " blocked",
                st.runnable() + " \u8FD0\u884C \u00B7 " + st.blocked() + " \u963B\u585E", "");
        metric(b, "DEADLOCKED", "\u6B7B\u9501", Integer.toString(st.deadlocked()),
                st.deadlocked() > 0 ? "lock cycle detected" : "none",
                st.deadlocked() > 0 ? "\u68C0\u6D4B\u5230\u9501\u73AF" : "\u65E0",
                st.deadlocked() > 0 ? "critical" : "");
        metric(b, "HEAP USED", "\u5806\u4F7F\u7528", Format.pct(st.heapRatio()),
                Format.mb(st.heapUsed()) + " / " + Format.mb(st.heapMax()),
                Format.mb(st.heapUsed()) + " / " + Format.mb(st.heapMax()),
                st.heapRatio() >= 0.85 ? "warn" : "");
        metric(b, "HOT STACKS", "\u70ED\u70B9\u6808", Integer.toString(st.hotspots()),
                "across " + st.samples() + (st.samples() == 1 ? " snapshot" : " samples"),
                "\u8DE8 " + st.samples() + " \u6B21\u91C7\u6837", "");
        metric(b, "CONTENDED LOCKS", "\u7ADE\u4E89\u9501", Integer.toString(st.contendedLocks()),
                r.contentions().isEmpty() ? "no pile-up" : "monitors with 3+ waiters",
                r.contentions().isEmpty() ? "\u65E0\u5806\u79EF" : "3 \u4E2A\u4EE5\u4E0A\u7B49\u5F85\u8005\u7684 monitor", "");
        b.append("</div>\n");
    }

    private static void metric(StringBuilder b, String keyEn, String keyZh, String value,
                               String subEn, String subZh, String cls) {
        b.append("<div class=\"metric\"><div class=\"k\" data-en=\"").append(esc(keyEn))
                .append("\" data-zh=\"").append(esc(keyZh)).append("\">").append(esc(keyEn)).append("</div>")
                .append("<div class=\"v ").append(cls).append("\">").append(esc(value)).append("</div>")
                .append("<div class=\"s\" data-en=\"").append(esc(subEn)).append("\" data-zh=\"")
                .append(esc(subZh)).append("\">").append(esc(subEn)).append("</div></div>\n");
    }

    private static void renderFacts(StringBuilder b, Snapshot s) {
        VmInfo vm = s.vm();
        HeapInfo heap = s.heap();
        b.append("<div class=\"facts\">")
                .append("<span>JAVA <b>").append(esc(vm.javaVersion())).append("</b></span>")
                .append("<span>VM <b>").append(esc(vm.vmName())).append("</b></span>")
                .append("<span>GC <b>").append(esc(vm.gcName())).append("</b></span>")
                .append("<span><span data-en=\"UPTIME\" data-zh=\"运行时长\">UPTIME</span> <b>").append(esc(vm.uptimeDisplay())).append("</b></span>")
                .append("<span>CPUS <b>").append(vm.processors()).append("</b></span>")
                .append("<span><span data-en=\"HEAP\" data-zh=\"堆\">HEAP</span> <b>").append(esc(heap.collector())).append("</b></span>")
                .append("<span><span data-en=\"MAIN\" data-zh=\"主类\">MAIN</span> <b>").append(esc(vm.displayName())).append("</b></span>")
                .append("</div>\n");
    }

    private static void renderFindings(StringBuilder b, AnalysisResult r) {
        b.append("<section id=\"findings\"><div class=\"section-head\"><h2 data-en=\"Findings\" data-zh=\"发现\">Findings</h2>")
                .append("<span class=\"count\">").append(String.format(Locale.ROOT, "%02d", r.findings().size()))
                .append("</span></div>\n");

        Finding first = r.findings().get(0);
        if (first.severity() != Severity.INFO && !first.fixes().isEmpty()) {
            b.append("<div class=\"callout\"><div class=\"tag\" data-en=\"DO THIS FIRST\" data-zh=\"先做这些\">DO THIS FIRST</div><ol>");
            for (int i = 0; i < Math.min(3, first.fixes().size()); i++) {
                b.append("<li data-en=\"").append(esc(first.fixes().get(i))).append("\" data-zh=\"")
                        .append(esc(fixZh(first, i))).append("\">").append(esc(first.fixes().get(i))).append("</li>");
            }
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
                .append("<h3 data-en=\"").append(esc(f.title())).append("\" data-zh=\"").append(esc(f.titleZh()))
                .append("\">").append(esc(f.title())).append("</h3>\n")
                .append("<p class=\"summary\" data-en=\"").append(esc(f.summary())).append("\" data-zh=\"")
                .append(esc(f.summaryZh())).append("\">").append(esc(f.summary())).append("</p>\n");

        if (!f.evidence().isEmpty()) {
            b.append("<div class=\"evidence\"><span class=\"label\" data-en=\"EVIDENCE\" data-zh=\"证据\">EVIDENCE</span><pre data-en=\"")
                    .append(esc(String.join("\n", f.evidence()))).append("\" data-zh=\"")
                    .append(esc(String.join("\n", f.evidenceZh()))).append("\">")
                    .append(esc(String.join("\n", f.evidence()))).append("</pre></div>\n");
        }
        if (!f.impact().isBlank()) {
            b.append("<div class=\"impact\"><span class=\"label\" data-en=\"WHY IT MATTERS\" data-zh=\"为什么重要\">WHY IT MATTERS</span><p data-en=\"")
                    .append(esc(f.impact())).append("\" data-zh=\"").append(esc(f.impactZh())).append("\">")
                    .append(esc(f.impact())).append("</p></div>\n");
        }
        if (!f.fixes().isEmpty()) {
            b.append("<div class=\"fix\"><span class=\"label\" data-en=\"FIX\" data-zh=\"修复建议\">FIX</span><ol>");
            for (int i = 0; i < f.fixes().size(); i++) {
                b.append("<li data-en=\"").append(esc(f.fixes().get(i))).append("\" data-zh=\"")
                        .append(esc(fixZh(f, i))).append("\">").append(esc(f.fixes().get(i))).append("</li>");
            }
            b.append("</ol></div>\n");
        }
        if (!f.threads().isEmpty()) {
            b.append("<div class=\"chips\">");
            f.threads().stream().limit(12).forEach(t -> b.append("<span class=\"chip\">").append(esc(t)).append("</span>"));
            if (f.threads().size() > 12) {
                b.append("<span class=\"chip\"><span data-en=\"+\" data-zh=\"+\">+</span>").append(f.threads().size() - 12)
                        .append("<span data-en=\" more\" data-zh=\" 个\"> more</span></span>");
            }
            b.append("</div>\n");
        }
        if (f.docs() != null && !f.docs().isBlank()) {
            b.append("<a class=\"doclink\" href=\"").append(BuildInfo.DOCS).append(esc(f.docs()))
                    .append("\" target=\"_blank\" rel=\"noopener\" data-en=\"PLAYBOOK \u2192\" data-zh=\"排查手册 \u2192\">PLAYBOOK \u2192</a>\n");
        }
        b.append("</article>\n");
    }

    private static String fixZh(Finding f, int index) {
        List<String> zh = f.fixesZh();
        return index < zh.size() ? zh.get(index) : f.fixes().get(index);
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
        b.append("<section id=\"anatomy\"><div class=\"section-head\"><h2 data-en=\"Thread anatomy\" data-zh=\"线程解剖\">Thread anatomy</h2>")
                .append("<span class=\"count\" data-en=\"").append(st.totalThreads()).append(" threads\" data-zh=\"")
                .append(st.totalThreads()).append(" 个线程\">").append(st.totalThreads()).append(" threads</span></div>\n")
                .append("<div class=\"grid2\">\n");

        b.append("<div class=\"panel\"><h3><span data-en=\"States\" data-zh=\"状态\">States</span><span data-en=\"at capture\" data-zh=\"采集时\">at capture</span></h3><div class=\"donut-wrap\">")
                .append("<div class=\"donut\" style=\"--donut:").append(donutGradient(counts, st.totalThreads())).append("\">")
                .append("<div class=\"hole\"><b>").append(st.totalThreads()).append("</b><span data-en=\"THREADS\" data-zh=\"线程\">THREADS</span></div></div>")
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

        b.append("<div class=\"panel\"><h3><span data-en=\"Top on-CPU stacks\" data-zh=\"CPU hotspot 栈\">Top on-CPU stacks</span><span>");
        if (s.sampleCount() > 1) {
            b.append("<span data-en=\"").append(s.sampleCount()).append(" samples\" data-zh=\"")
                    .append(s.sampleCount()).append(" 次采样\">").append(s.sampleCount()).append(" samples</span>");
        } else {
            b.append("<span data-en=\"single snapshot\" data-zh=\"单次快照\">single snapshot</span>");
        }
        b.append("</span></h3><div class=\"bars\">");
        List<Hotspot> hotspots = r.hotspots();
        if (hotspots.isEmpty()) {
            b.append("<div class=\"empty\" data-en=\"No stack stayed on-CPU across the window. This is normal for I/O-bound services.\" ")
                    .append("data-zh=\"整个窗口内没有调用栈持续占用 CPU。对 I/O 密集型服务来说这是正常的。\">")
                    .append("No stack stayed on-CPU across the window. This is normal for I/O-bound services.</div>");
        } else {
            int max = hotspots.stream().mapToInt(Hotspot::threads).max().orElse(1);
            for (Hotspot h : hotspots.stream().limit(6).toList()) {
                int width = Math.max(4, (int) Math.round(h.threads() * 100.0 / max));
                b.append("<div class=\"bar\"><div class=\"row\"><span class=\"name\">").append(esc(h.topFrame()))
                        .append("</span><span class=\"num\">").append(h.threads())
                        .append("<span data-en=\" thr \u00B7 \" data-zh=\" 线程 \u00B7 \"> thr \u00B7 </span>")
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
        b.append("<section id=\"locks\"><div class=\"section-head\"><h2 data-en=\"Lock map\" data-zh=\"锁地图\">Lock map</h2>")
                .append("<span class=\"count\" data-en=\"").append(contentions.size()).append(" contended\" data-zh=\"")
                .append(contentions.size()).append(" 个竞争锁\">").append(contentions.size()).append(" contended</span></div>\n")
                .append("<div class=\"grid2\">\n");

        b.append("<div class=\"panel\"><h3><span data-en=\"Wait-for chains\" data-zh=\"等待链\">Wait-for chains</span><span data-en=\"first snapshot\" data-zh=\"首次快照\">first snapshot</span></h3>");
        if (chains.isEmpty()) {
            b.append("<div class=\"empty\" data-en=\"No thread is waiting for a monitor owned by another thread.\" ")
                    .append("data-zh=\"没有线程在等待另一个线程持有的 monitor。\">")
                    .append("No thread is waiting for a monitor owned by another thread.</div>");
        } else {
            b.append("<div class=\"flow\">");
            for (String chain : chains) {
                b.append(chain).append("<br>");
            }
            b.append("</div>");
        }
        b.append("</div>\n");

        b.append("<div class=\"panel\"><h3><span data-en=\"Most contended monitors\" data-zh=\"竞争最激烈的 monitor\">Most contended monitors</span><span data-en=\"3+ waiters\" data-zh=\"3 个以上等待者\">3+ waiters</span></h3><div class=\"locks\">");
        if (contentions.isEmpty()) {
            b.append("<div class=\"empty\" data-en=\"No monitor has three or more waiters.\" data-zh=\"没有 monitor 拥有三个或以上等待者。\">")
                    .append("No monitor has three or more waiters.</div>");
        } else {
            for (Contention c : contentions.stream().limit(6).toList()) {
                b.append("<div class=\"lock\"><div class=\"id\">&lt;").append(esc(c.lockId())).append("&gt; ")
                        .append(esc(c.lockClass())).append("</div><div class=\"n\">").append(c.waiters())
                        .append("<span data-en=\" waiters\" data-zh=\" 个等待者\"> waiters</span></div><div class=\"who\">")
                        .append("<span data-en=\"held by\" data-zh=\"持有者\">held by</span> \"").append(esc(c.ownerName()))
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
        b.append("<section id=\"threads\"><div class=\"section-head\"><h2 data-en=\"All threads\" data-zh=\"全部线程\">All threads</h2>")
                .append("<span class=\"count\"><span id=\"thread-visible\">").append(dump.size())
                .append("</span> / ").append(dump.size()).append("</span>")
                .append("<div class=\"filters\">")
                .append("<button class=\"filter on\" data-filter=\"ALL\">ALL</button>")
                .append("<button class=\"filter\" data-filter=\"RUNNABLE\">RUNNABLE</button>")
                .append("<button class=\"filter\" data-filter=\"BLOCKED\">BLOCKED</button>")
                .append("<button class=\"filter\" data-filter=\"WAITING\">WAITING</button>")
                .append("<button class=\"filter\" data-filter=\"TIMED_WAITING\">TIMED</button>")
                .append("<input id=\"thread-search\" type=\"search\" placeholder=\"filter by name or frame\u2026\"")
                .append(" data-en-placeholder=\"filter by name or frame\u2026\" data-zh-placeholder=\"按线程名或栈帧筛选\u2026\" autocomplete=\"off\">")
                .append("</div></div>\n");

        if (dump.size() > MAX_THREAD_ROWS) {
            b.append("<div class=\"empty\" data-en=\"Showing the first ").append(MAX_THREAD_ROWS).append(" of ")
                    .append(dump.size()).append(" threads. The full dump is saved next to this report.\" data-zh=\"")
                    .append("只显示前 ").append(MAX_THREAD_ROWS).append(" / ").append(dump.size())
                    .append(" 个线程，完整 dump 已保存在报告旁边。\">")
                    .append("Showing the first ").append(MAX_THREAD_ROWS).append(" of ")
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
            b.append("<span data-en=\"(no Java frames)\" data-zh=\"（无 Java 栈帧）\">(no Java frames)</span>");
        } else {
            for (StackFrame frame : t.frames()) {
                b.append("    ").append(esc(frame.raw())).append('\n');
            }
        }
        b.append("</pre></div></details>\n");
    }

    private static void renderFooter(StringBuilder b, Snapshot s, AnalysisResult r) {
        b.append("<footer><div class=\"row\"><div>")
                .append("<span data-en=\"Generated by\" data-zh=\"由\">Generated by</span> ")
                .append(BuildInfo.NAME).append(" v").append(BuildInfo.VERSION)
                .append(" \u00B7 ").append(TIME.format(s.capturedAt().atZone(ZoneId.systemDefault())))
                .append("<br><span data-en=\"Self-contained report. No data left this machine.\" data-zh=\"自包含报告，数据未离开本机。\">Self-contained report. No data left this machine.</span>")
                .append("</div><div style=\"text-align:right\">")
                .append("PID ").append(s.vm().pid()).append(" \u00B7 ").append(esc(s.vm().displayName()))
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

    private static String aiPromptZh(Snapshot s, AnalysisResult r) {
        StringBuilder b = new StringBuilder();
        b.append("# JVM 事故报告\n\n");
        b.append("判定：").append(r.verdict()).append("\n");
        b.append("PID：").append(s.vm().pid()).append("  主类：").append(s.vm().displayName())
                .append("  Java：").append(s.vm().javaVersion()).append("  GC：").append(s.vm().gcName())
                .append("  运行时长：").append(s.vm().uptimeDisplay()).append("\n");
        b.append("线程：").append(r.stats().totalThreads()).append("（").append(r.stats().runnable())
                .append(" 运行中、").append(r.stats().blocked()).append(" 阻塞）  堆：")
                .append(Format.mb(r.stats().heapUsed())).append(" / ").append(Format.mb(r.stats().heapMax()))
                .append("  采样：").append(r.stats().samples()).append("\n\n");
        b.append("## 发现\n");
        for (Finding f : r.findings()) {
            b.append("\n### [").append(f.severity().label().toUpperCase(Locale.ROOT)).append("] ").append(f.titleZh()).append("\n");
            b.append(f.summaryZh()).append("\n\n");
            b.append("证据：\n");
            for (String e : f.evidenceZh()) {
                b.append("- ").append(e).append("\n");
            }
            b.append("\n影响：").append(f.impactZh()).append("\n");
            b.append("建议修复：\n");
            for (String fix : f.fixesZh()) {
                b.append("- ").append(fix).append("\n");
            }
        }
        b.append("\n## CPU hotspot 栈\n");
        for (Hotspot h : r.hotspots().stream().limit(8).toList()) {
            b.append("- ").append(h.threads()).append(" 个线程，").append(h.samples()).append("/")
                    .append(r.stats().samples()).append(" 次采样：").append(h.topFrame()).append("\n");
        }
        b.append("\n请以资深 JVM 性能工程师的身份分析：找出最可能的根因，按风险排序修复方案，")
                .append("并给出确认每个假设所需的具体命令或查询。如果需要更多证据，请索要完整线程 dump。\n");
        return b.toString();
    }

    private static String verdictZh(String verdict) {
        // Verdict tokens stay in English; only the prose around them is translated.
        return verdict;
    }

    private static String severityLineZh(AnalysisResult r) {
        long critical = r.count(Severity.CRITICAL);
        long high = r.count(Severity.HIGH);
        long medium = r.count(Severity.MEDIUM);
        if (critical + high + medium == 0) {
            return "\u65E0\u963B\u585E\u4FE1\u53F7";
        }
        return critical + " CRITICAL \u00B7 " + high + " HIGH \u00B7 " + medium + " MEDIUM";
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
