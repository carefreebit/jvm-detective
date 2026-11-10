package io.github.carefreebit.jvmd.analyze;

import java.util.List;

/** Complete result of one analysis pass. */
public record AnalysisResult(List<Finding> findings, Stats stats, List<Hotspot> hotspots,
                             List<Contention> contentions, String verdict, String narrative) {

    public AnalysisResult {
        findings = List.copyOf(findings);
        hotspots = List.copyOf(hotspots);
        contentions = List.copyOf(contentions);
    }

    public Severity worst() {
        return findings.stream().map(Finding::severity)
                .max(java.util.Comparator.comparingInt(Severity::rank)).orElse(Severity.INFO);
    }

    public long count(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }
}
