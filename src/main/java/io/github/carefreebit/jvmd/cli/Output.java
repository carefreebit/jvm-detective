package io.github.carefreebit.jvmd.cli;

import io.github.carefreebit.jvmd.analyze.AnalysisResult;
import io.github.carefreebit.jvmd.model.Snapshot;
import io.github.carefreebit.jvmd.report.JsonReport;
import io.github.carefreebit.jvmd.report.MarkdownReport;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;

/** Shared stdout / desktop helpers. */
final class Output {

    private Output() {
    }

    /** True when stdout must stay machine-readable, so the human summary moves to stderr. */
    static boolean machine(String mode) {
        return mode != null && (mode.equalsIgnoreCase("json") || mode.equalsIgnoreCase("md")
                || mode.equalsIgnoreCase("markdown"));
    }

    static void printExtra(String mode, Snapshot snapshot, AnalysisResult result) {
        if (mode == null) {
            return;
        }
        switch (mode.toLowerCase()) {
            case "md", "markdown" -> System.out.println(MarkdownReport.render(snapshot, result));
            case "json" -> System.out.println(JsonReport.render(snapshot, result));
            case "none" -> {
            }
            default -> System.err.println("  ! unknown --print mode: " + mode + " (use md or json)");
        }
    }

    static void open(Path file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(file.toUri().toString()));
                return;
            }
        } catch (Exception ignored) {
            // fall through to the printed path
        }
        System.err.println("  ! cannot open a browser here; open " + file + " manually");
    }
}
