package io.github.carefreebit.jvmd.analyze;

import java.util.ArrayList;
import java.util.List;

/** One ranked, evidence-backed incident finding. */
public record Finding(String id, Severity severity, String category, String title, String summary,
                      List<String> evidence, String impact, List<String> fixes,
                      List<String> threads, List<Long> threadIds, String docs, Localized zh) {

    /** Chinese copy of a finding. Missing pieces fall back to the English text. */
    public record Localized(String title, String summary, List<String> evidence, String impact,
                            List<String> fixes) {
        public Localized {
            title = title == null ? "" : title;
            summary = summary == null ? "" : summary;
            evidence = evidence == null ? List.of()
                    : evidence.stream().filter(line -> line != null && !line.isBlank()).toList();
            impact = impact == null ? "" : impact;
            fixes = fixes == null ? List.of() : List.copyOf(fixes);
        }
    }

    public Finding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        fixes = fixes == null ? List.of() : List.copyOf(fixes);
        threads = threads == null ? List.of() : List.copyOf(threads);
        threadIds = threadIds == null ? List.of() : List.copyOf(threadIds);
    }

    public String titleZh() {
        return zh != null && !zh.title().isBlank() ? zh.title() : title;
    }

    public String summaryZh() {
        return zh != null && !zh.summary().isBlank() ? zh.summary() : summary;
    }

    public List<String> evidenceZh() {
        return zh != null && !zh.evidence().isEmpty() ? zh.evidence() : evidence;
    }

    public String impactZh() {
        return zh != null && !zh.impact().isBlank() ? zh.impact() : impact;
    }

    public List<String> fixesZh() {
        return zh != null && !zh.fixes().isEmpty() ? zh.fixes() : fixes;
    }

    public static Builder builder(String id, Severity severity, String category, String title) {
        return new Builder(id, severity, category, title);
    }

    public static final class Builder {
        private final String id;
        private final Severity severity;
        private final String category;
        private final String title;
        private String summary = "";
        private final List<String> evidence = new ArrayList<>();
        private String impact = "";
        private final List<String> fixes = new ArrayList<>();
        private final List<String> threads = new ArrayList<>();
        private final List<Long> threadIds = new ArrayList<>();
        private String docs = "";
        private String zhTitle = "";
        private String zhSummary = "";
        private final List<String> zhEvidence = new ArrayList<>();
        private String zhImpact = "";
        private final List<String> zhFixes = new ArrayList<>();

        private Builder(String id, Severity severity, String category, String title) {
            this.id = id;
            this.severity = severity;
            this.category = category;
            this.title = title;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder evidence(String... lines) {
            this.evidence.addAll(List.of(lines));
            return this;
        }

        public Builder evidence(List<String> lines) {
            this.evidence.addAll(lines);
            return this;
        }

        public Builder impact(String impact) {
            this.impact = impact;
            return this;
        }

        public Builder fix(String... fixes) {
            this.fixes.addAll(List.of(fixes));
            return this;
        }

        public Builder threads(List<String> names) {
            this.threads.addAll(names);
            return this;
        }

        public Builder threadIds(List<Long> ids) {
            this.threadIds.addAll(ids);
            return this;
        }

        public Builder docs(String docs) {
            this.docs = docs;
            return this;
        }

        public Builder zhTitle(String title) {
            this.zhTitle = title;
            return this;
        }

        public Builder zhSummary(String summary) {
            this.zhSummary = summary;
            return this;
        }

        public Builder zhEvidence(String... lines) {
            this.zhEvidence.addAll(List.of(lines));
            return this;
        }

        public Builder zhImpact(String impact) {
            this.zhImpact = impact;
            return this;
        }

        public Builder zhFix(String... fixes) {
            this.zhFixes.addAll(List.of(fixes));
            return this;
        }

        public Finding build() {
            Localized localized = new Localized(zhTitle, zhSummary, zhEvidence, zhImpact, zhFixes);
            return new Finding(id, severity, category, title, summary, evidence, impact, fixes,
                    threads, threadIds, docs, localized);
        }
    }
}
