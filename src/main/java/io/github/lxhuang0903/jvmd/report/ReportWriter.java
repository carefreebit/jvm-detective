package io.github.lxhuang0903.jvmd.report;

import io.github.lxhuang0903.jvmd.analyze.AnalysisResult;
import io.github.lxhuang0903.jvmd.model.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Persists one investigation as a folder of shareable artefacts. */
public final class ReportWriter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ReportWriter() {
    }

    public record Output(Path dir, Path html, Path markdown, Path json, Path threadDump) {
    }

    public static Output write(Snapshot snapshot, AnalysisResult result, Path outRoot) throws IOException {
        String caseId = HtmlReport.caseId(snapshot);
        String stamp = STAMP.format(snapshot.capturedAt().atZone(ZoneId.systemDefault()));
        Path dir = outRoot.resolve("case-" + caseId.toLowerCase() + "-" + stamp);
        Files.createDirectories(dir);

        Path html = dir.resolve("report.html");
        Path markdown = dir.resolve("report.md");
        Path json = dir.resolve("report.json");
        Path dump = dir.resolve("thread-dump.txt");

        Files.writeString(html, HtmlReport.render(snapshot, result), StandardCharsets.UTF_8);
        Files.writeString(markdown, MarkdownReport.render(snapshot, result), StandardCharsets.UTF_8);
        Files.writeString(json, JsonReport.render(snapshot, result), StandardCharsets.UTF_8);
        Files.writeString(dump, snapshot.first().raw(), StandardCharsets.UTF_8);

        return new Output(dir, html, markdown, json, dump);
    }
}
