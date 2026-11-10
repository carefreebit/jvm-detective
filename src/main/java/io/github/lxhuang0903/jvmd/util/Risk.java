package io.github.lxhuang0903.jvmd.util;

import io.github.lxhuang0903.jvmd.analyze.AnalysisResult;
import io.github.lxhuang0903.jvmd.analyze.Finding;
import io.github.lxhuang0903.jvmd.analyze.Severity;

/** One scoring model shared by every report format. */
public final class Risk {

    private Risk() {
    }

    public static int score(AnalysisResult result) {
        int score = 0;
        for (Finding finding : result.findings()) {
            score += switch (finding.severity()) {
                case CRITICAL -> 75;
                case HIGH -> 34;
                case MEDIUM -> 14;
                case LOW -> 5;
                case INFO -> 0;
            };
            // A deadlock is unrecoverable without a restart: score it as a full outage.
            if ("deadlock".equals(finding.id())) {
                score += 15;
            }
        }
        if (result.stats().heapRatio() >= 0.85) {
            score += 10;
        }
        if (result.stats().blocked() >= 10) {
            score += 6;
        }
        return Math.min(100, score);
    }

    public static String color(int score) {
        if (score >= 70) {
            return "#ff4d4f";
        }
        if (score >= 45) {
            return "#ff7a45";
        }
        if (score >= 25) {
            return "#f5b942";
        }
        return "#3ddc97";
    }

    public static String label(int score) {
        if (score >= 70) {
            return "SEV-1";
        }
        if (score >= 45) {
            return "SEV-2";
        }
        if (score >= 25) {
            return "SEV-3";
        }
        return "SEV-4";
    }

    /** Severity used only for colouring a zero-finding capture. */
    public static Severity worst(AnalysisResult result) {
        return result.findings().stream().map(Finding::severity)
                .max(java.util.Comparator.comparingInt(Severity::rank)).orElse(Severity.INFO);
    }
}
