package io.github.carefreebit.jvmd.analyze;

import java.util.ArrayList;
import java.util.List;

/** One ranked, evidence-backed incident finding. */
public record Finding(String id, Severity severity, String category, String title, String summary,
                      List<String> evidence, String impact, List<String> fixes,
                      List<String> threads, List<Long> threadIds, String docs) {

    public Finding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        fixes = fixes == null ? List.of() : List.copyOf(fixes);
        threads = threads == null ? List.of() : List.copyOf(threads);
        threadIds = threadIds == null ? List.of() : List.copyOf(threadIds);
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

        public Finding build() {
            return new Finding(id, severity, category, title, summary, evidence, impact, fixes,
                    threads, threadIds, docs);
        }
    }
}
