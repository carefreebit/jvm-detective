package io.github.lxhuang0903.jvmd.cli;

import io.github.lxhuang0903.jvmd.analyze.AnalysisResult;
import io.github.lxhuang0903.jvmd.analyze.Analyzer;
import io.github.lxhuang0903.jvmd.collect.Collector;
import io.github.lxhuang0903.jvmd.demo.DemoApp;
import io.github.lxhuang0903.jvmd.model.Snapshot;
import io.github.lxhuang0903.jvmd.report.ReportWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/** {@code jvmd demo <scenario>} — reproducible incidents, no external services required. */
@Command(name = "demo", description = "Start a reproducible incident in this JVM, investigate it and write a report.", mixinStandardHelpOptions = true)
final class DemoCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = "deadlock",
            paramLabel = "SCENARIO", description = "deadlock | cpu-spin | thread-leak | lock-contention | pool-starvation | downstream-block")
    String scenario;

    @Mixin
    CommonOptions common = new CommonOptions();

    @Option(names = "--list", description = "List available scenarios and exit.")
    boolean list;

    @Option(names = "--open", description = "Open the HTML report in the default browser.")
    boolean open;

    @Override
    public Integer call() throws Exception {
        if (list) {
            System.out.println("Scenarios:");
            for (String name : DemoApp.scenarios()) {
                System.out.printf("  %-18s %s%n", name, DemoApp.describe(name));
            }
            return 0;
        }
        if (!DemoApp.scenarios().contains(scenario)) {
            System.err.println("Unknown scenario: " + scenario);
            System.err.println("Run `jvmd demo --list` to see all scenarios.");
            return 64;
        }

        System.err.println("  > starting incident: " + scenario + " - " + DemoApp.describe(scenario));
        try (DemoApp.Incident incident = DemoApp.start(scenario)) {
            Thread.sleep(900);
            System.err.println("  > " + incident.threadCount() + " demo threads are alive; sampling this JVM");
            Snapshot snapshot = Collector.collect(ProcessHandle.current().pid(), common.samples,
                    common.intervalMs, message -> System.err.println("  > " + message));
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
}
