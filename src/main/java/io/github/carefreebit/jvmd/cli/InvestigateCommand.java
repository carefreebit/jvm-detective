package io.github.carefreebit.jvmd.cli;

import io.github.carefreebit.jvmd.analyze.AnalysisResult;
import io.github.carefreebit.jvmd.analyze.Analyzer;
import io.github.carefreebit.jvmd.collect.Collector;
import io.github.carefreebit.jvmd.model.Snapshot;
import io.github.carefreebit.jvmd.report.ReportWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** {@code jvmd investigate <pid>} — the main event. */
@Command(name = "investigate", description = "Sample a live JVM, rank its problems and write an incident report.", mixinStandardHelpOptions = true)
final class InvestigateCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "PID", description = "Target JVM process id (see `jvmd list`).")
    long pid;

    @Mixin
    CommonOptions common = new CommonOptions();

    @Option(names = "--open", description = "Open the HTML report in the default browser.")
    boolean open;

    @Option(names = {"-q", "--quiet"}, description = "Do not print sampling progress.")
    boolean quiet;

    @Override
    public Integer call() throws Exception {
        Consumer<String> log = quiet ? message -> {
        } : message -> System.err.println("  > " + message);

        Snapshot snapshot = Collector.collect(pid, common.samples, common.intervalMs, log);
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
