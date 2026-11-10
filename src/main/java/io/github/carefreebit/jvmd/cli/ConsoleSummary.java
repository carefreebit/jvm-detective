package io.github.carefreebit.jvmd.cli;

import io.github.carefreebit.jvmd.analyze.AnalysisResult;
import io.github.carefreebit.jvmd.analyze.Finding;
import io.github.carefreebit.jvmd.analyze.Hotspot;
import io.github.carefreebit.jvmd.analyze.Severity;
import io.github.carefreebit.jvmd.model.Snapshot;
import io.github.carefreebit.jvmd.report.ReportWriter;
import io.github.carefreebit.jvmd.util.BuildInfo;
import io.github.carefreebit.jvmd.util.Format;

import java.util.List;

/** Terminal rendering: the 20-second version of the HTML report. */
final class ConsoleSummary {

    private ConsoleSummary() {
    }

    static void print(Snapshot snapshot, AnalysisResult result, ReportWriter.Output output) {
        print(System.out, snapshot, result, output);
    }

    static void print(java.io.PrintStream out, Snapshot snapshot, AnalysisResult result, ReportWriter.Output output) {
        out.println();
        out.println("  JVM DETECTIVE  v" + BuildInfo.VERSION + "   case " + io.github.carefreebit.jvmd.report.HtmlReport.caseId(snapshot));
        out.println("  " + "-".repeat(72));
        out.println("  target    " + snapshot.vm().displayName() + "  (pid " + snapshot.vm().pid() + ")");
        out.println("  runtime   " + snapshot.vm().javaVersion() + "  |  " + snapshot.vm().gcName()
                + "  |  up " + snapshot.vm().uptimeDisplay() + "  |  " + snapshot.vm().processors() + " cpus");
        out.println("  threads   " + result.stats().totalThreads() + " total  |  "
                + result.stats().runnable() + " runnable  |  " + result.stats().blocked() + " blocked  |  "
                + result.stats().waiting() + " waiting  |  " + result.stats().deadlocked() + " deadlocked");
        out.println("  heap      " + Format.mb(result.stats().heapUsed()) + " / "
                + Format.mb(result.stats().heapMax()) + "  (" + Format.pct(result.stats().heapRatio()) + ")");
        out.println();

        Severity worst = result.findings().isEmpty() ? Severity.INFO : result.findings().get(0).severity();
        out.println("  VERDICT   " + result.verdict() + "   [" + worst.label().toUpperCase() + "]");
        out.println("  " + wrap(result.narrative(), 88, "  "));
        out.println();

        List<Finding> findings = result.findings();
        int shown = 0;
        for (Finding f : findings) {
            if (shown++ >= 6) {
                out.println("  ... " + (findings.size() - 6) + " more findings in the report");
                break;
            }
            out.println("  " + badge(f.severity()) + " " + f.title());
            if (!f.evidence().isEmpty()) {
                out.println("           " + abbreviate(f.evidence().get(0), 96));
            }
            if (!f.fixes().isEmpty()) {
                out.println("           fix: " + abbreviate(f.fixes().get(0), 90));
            }
            out.println();
        }

        if (!result.hotspots().isEmpty()) {
            Hotspot top = result.hotspots().get(0);
            out.println("  HOTTEST   " + top.threads() + " threads in " + top.topFrame());
            out.println("            seen in " + top.samples() + "/" + snapshot.sampleCount() + " samples");
            out.println();
        }

        out.println("  " + "-".repeat(72));
        out.println("  report    " + output.html());
        out.println("  markdown  " + output.markdown());
        out.println("  json      " + output.json());
        out.println();
    }

    static int exitCode(AnalysisResult result) {
        if (result.count(Severity.CRITICAL) > 0) {
            return 2;
        }
        if (result.count(Severity.HIGH) > 0 || result.count(Severity.MEDIUM) > 0) {
            return 1;
        }
        return 0;
    }

    private static String badge(Severity severity) {
        return switch (severity) {
            case CRITICAL -> "[ CRITICAL ]";
            case HIGH -> "[  HIGH    ]";
            case MEDIUM -> "[  MEDIUM  ]";
            case LOW -> "[  LOW     ]";
            case INFO -> "[  INFO    ]";
        };
    }

    private static String abbreviate(String text, int max) {
        String clean = text.replace('\n', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max - 3) + "...";
    }

    private static String wrap(String text, int width, String indent) {
        StringBuilder b = new StringBuilder();
        int line = 0;
        for (String word : text.split(" ")) {
            if (line > 0 && line + word.length() + 1 > width) {
                b.append('\n').append(indent);
                line = 0;
            } else if (line > 0) {
                b.append(' ');
                line++;
            }
            b.append(word);
            line += word.length();
        }
        return b.toString();
    }
}
