package io.github.carefreebit.jvmd.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carefreebit.jvmd.analyze.AnalysisResult;
import io.github.carefreebit.jvmd.model.Snapshot;
import io.github.carefreebit.jvmd.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Report writers")
class ReportTest {

    @Test
    @DisplayName("a deadlock capture writes all four artefacts")
    void writesEveryArtefact(@TempDir Path temp) throws IOException {
        Snapshot snapshot = TestFixtures.deadlockSnapshot();
        AnalysisResult result = TestFixtures.analyze(snapshot);

        ReportWriter.Output output = ReportWriter.write(snapshot, result, temp);

        assertTrue(Files.isDirectory(output.dir()));
        assertTrue(Files.size(output.html()) > 10_000, "the HTML bundle should be self-contained");
        assertTrue(Files.size(output.markdown()) > 500);
        assertTrue(Files.size(output.json()) > 500);
        assertEquals(snapshot.first().raw(), Files.readString(output.threadDump(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the HTML report is offline, accessible and carries the AI handoff")
    void htmlIsSelfContained(@TempDir Path temp) throws IOException {
        Snapshot snapshot = TestFixtures.deadlockSnapshot();
        AnalysisResult result = TestFixtures.analyze(snapshot);
        String html = Files.readString(ReportWriter.write(snapshot, result, temp).html(),
                StandardCharsets.UTF_8);

        assertTrue(html.toLowerCase(java.util.Locale.ROOT).startsWith("<!doctype html>"));
        assertTrue(html.contains("<title>"), "a title helps tickets and browser tabs");
        assertTrue(html.contains("Threads are deadlocked"));
        assertTrue(html.contains("id=\"ai-prompt\""), "copy-AI-prompt payload is missing");
        assertTrue(html.contains("<style>"), "CSS must be inlined");
        assertTrue(html.contains("addEventListener"), "JS must be inlined");
        assertFalse(html.contains("cdn.jsdelivr"), "no CDN dependencies");
        assertFalse(html.contains("googleapis"), "no remote fonts");
        assertFalse(html.contains("<img src=\"http"), "no remote images");
    }

    @Test
    @DisplayName("JSON is machine readable and shares the risk score")
    void jsonIsStable(@TempDir Path temp) throws IOException {
        Snapshot snapshot = TestFixtures.deadlockSnapshot();
        AnalysisResult result = TestFixtures.analyze(snapshot);
        Path json = ReportWriter.write(snapshot, result, temp).json();

        JsonNode root = new ObjectMapper().readTree(Files.readString(json, StandardCharsets.UTF_8));

        assertEquals("CRITICAL", root.get("verdict").asText());
        assertEquals(90, root.get("riskScore").asInt());
        assertEquals(2, root.get("stats").get("deadlocked").asInt());
        assertEquals("deadlock", root.get("findings").get(0).get("id").asText());
        assertEquals("CRITICAL", root.get("findings").get(0).get("severity").asText());
        assertTrue(root.get("findings").get(0).get("evidence").isArray());
        assertEquals("JVM Detective", root.get("tool").get("name").asText());
        assertEquals("1.0.0", root.get("tool").get("version").asText());
        assertEquals(1, root.get("samples").asInt());
    }

    @Test
    @DisplayName("Markdown starts with the verdict so it survives a Slack paste")
    void markdownOpensWithTheVerdict(@TempDir Path temp) throws IOException {
        Snapshot snapshot = TestFixtures.deadlockSnapshot();
        AnalysisResult result = TestFixtures.analyze(snapshot);
        String markdown = Files.readString(ReportWriter.write(snapshot, result, temp).markdown(),
                StandardCharsets.UTF_8);

        String firstLine = markdown.split("\n", 2)[0];
        assertEquals("# JVM Detective report — CRITICAL", firstLine);
        assertTrue(markdown.contains("## Findings"));
        assertTrue(markdown.contains("deadlock"));
    }

    @Test
    @DisplayName("a healthy capture still produces a readable report")
    void healthyCaptureIsNotAnEmptyFile(@TempDir Path temp) throws IOException {
        Snapshot snapshot = TestFixtures.idleSnapshot();
        AnalysisResult result = TestFixtures.analyze(snapshot);
        ReportWriter.Output output = ReportWriter.write(snapshot, result, temp);

        String html = Files.readString(output.html(), StandardCharsets.UTF_8);
        assertTrue(html.contains("HEALTHY"), "the verdict should be visible");
        assertTrue(html.contains("No incident signature detected"),
                "an INFO baseline explains what was checked");
    }
}
