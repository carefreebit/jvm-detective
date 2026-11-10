package io.github.carefreebit.jvmd.util;

import io.github.carefreebit.jvmd.analyze.AnalysisResult;
import io.github.carefreebit.jvmd.analyze.Finding;
import io.github.carefreebit.jvmd.analyze.Severity;
import io.github.carefreebit.jvmd.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Risk score")
class RiskTest {

    @Test
    @DisplayName("a deadlock is a full-outage score: 75 + 15")
    void deadlockScoresNinety() {
        AnalysisResult result = TestFixtures.analyze(TestFixtures.deadlockSnapshot());

        assertEquals(90, Risk.score(result));
        assertEquals("SEV-1", Risk.label(90));
    }

    @Test
    @DisplayName("a high finding plus twelve blocked threads scores 34 + 6")
    void highFindingScoresForty() {
        AnalysisResult result = TestFixtures.analyze(TestFixtures.contentionSnapshot());

        assertEquals(40, Risk.score(result));
        assertEquals("SEV-3", Risk.label(40));
    }

    @Test
    @DisplayName("a healthy capture scores zero and SEV-4")
    void healthyScoresZero() {
        AnalysisResult result = TestFixtures.analyze(TestFixtures.idleSnapshot());

        assertEquals(0, Risk.score(result));
        assertEquals("SEV-4", Risk.label(0));
    }

    @Test
    @DisplayName("the score clamps at 100 no matter how many findings there are")
    void clampsAtOneHundred() {
        AnalysisResult base = TestFixtures.analyze(TestFixtures.deadlockSnapshot());
        List<Finding> many = List.of(
                finding("deadlock", Severity.CRITICAL),
                finding("cpu-hotspot", Severity.CRITICAL),
                finding("heap-pressure", Severity.HIGH));
        AnalysisResult result = new AnalysisResult(many, base.stats(), base.hotspots(),
                base.contentions(), "CRITICAL", "", "");

        assertEquals(100, Risk.score(result));
        assertEquals("#ff4d4f", Risk.color(100));
    }

    @Test
    @DisplayName("bands are 25 / 45 / 70")
    void bands() {
        assertEquals("SEV-4", Risk.label(24));
        assertEquals("SEV-3", Risk.label(25));
        assertEquals("SEV-3", Risk.label(44));
        assertEquals("SEV-2", Risk.label(45));
        assertEquals("SEV-2", Risk.label(69));
        assertEquals("SEV-1", Risk.label(70));
    }

    @Test
    @DisplayName("worst finding follows severity rank, not enum order")
    void worstIsRankedBySeverity() {
        AnalysisResult base = TestFixtures.analyze(TestFixtures.deadlockSnapshot());
        AnalysisResult mixed = new AnalysisResult(
                List.of(finding("heap-pressure", Severity.MEDIUM), finding("deadlock", Severity.CRITICAL)),
                base.stats(), base.hotspots(), base.contentions(), "CRITICAL", "", "");

        assertEquals(Severity.CRITICAL, Risk.worst(mixed));
        assertEquals(Severity.CRITICAL, mixed.worst());
    }

    private static Finding finding(String id, Severity severity) {
        return Finding.builder(id, severity, "test", id).summary("test").build();
    }
}
