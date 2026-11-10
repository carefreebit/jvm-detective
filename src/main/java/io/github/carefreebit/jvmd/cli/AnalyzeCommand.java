package io.github.carefreebit.jvmd.cli;

import io.github.carefreebit.jvmd.analyze.AnalysisResult;
import io.github.carefreebit.jvmd.analyze.Analyzer;
import io.github.carefreebit.jvmd.model.HeapInfo;
import io.github.carefreebit.jvmd.model.Snapshot;
import io.github.carefreebit.jvmd.model.ThreadDump;
import io.github.carefreebit.jvmd.model.VmInfo;
import io.github.carefreebit.jvmd.parse.ThreadDumpParser;
import io.github.carefreebit.jvmd.report.ReportWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** {@code jvmd analyze <file>} — offline analysis of a dump someone pasted into the incident channel. */
@Command(name = "analyze", description = "Analyse an existing thread dump (jstack or jcmd output) without attaching.", mixinStandardHelpOptions = true)
final class AnalyzeCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Thread-dump text file (jcmd Thread.print -l or jstack -l).")
    Path file;

    @Mixin
    CommonOptions common = new CommonOptions();

    @Option(names = "--open", description = "Open the HTML report in the default browser.")
    boolean open;

    @Override
    public Integer call() throws Exception {
        if (!Files.isReadable(file)) {
            System.err.println("Cannot read " + file);
            return 64;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        ThreadDump dump = ThreadDumpParser.parse(text);
        if (dump.size() == 0) {
            System.err.println("No Java threads found in " + file);
            System.err.println("Expected output from `jcmd <pid> Thread.print -l` or `jstack -l <pid>`.");
            return 65;
        }

        VmInfo vm = new VmInfo(0, file.getFileName().toString(), "", "unknown", "unknown",
                System.getProperty("os.name"), System.getProperty("os.arch"), -1,
                Runtime.getRuntime().availableProcessors(), Map.of(), Map.of());
        Snapshot snapshot = new Snapshot(vm, HeapInfo.UNKNOWN, List.of(dump), Instant.now(),
                Map.of("thread_dump", text));

        AnalysisResult result = new Analyzer().analyze(snapshot);
        ReportWriter.Output output = ReportWriter.write(snapshot, result, common.out.toAbsolutePath());

        ConsoleSummary.print(Output.machine(common.print) ? System.err : System.out, snapshot, result, output);
        Output.printExtra(common.print, snapshot, result);
        if (open) {
            Output.open(output.html());
        }
        return ConsoleSummary.exitCode(result);
    }
}
