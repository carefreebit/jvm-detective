package io.github.carefreebit.jvmd.cli;

import io.github.carefreebit.jvmd.util.BuildInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point. One jar, no agent, no server: point it at a pid and get a verdict plus a report.
 */
@Command(
        name = "jvmd",
        mixinStandardHelpOptions = true,
        version = {"jvmd " + BuildInfo.VERSION, "Java 17+"},
        description = {
                "JVM Detective - turn a live JVM into an incident report.",
                "",
                "It samples thread dumps through jcmd, detects deadlocks, lock pile-ups, CPU spins,",
                "thread leaks and pool starvation, then writes one self-contained HTML report.",
                "",
                "Examples:",
                "  jvmd list",
                "  jvmd investigate 12345",
                "  jvmd investigate 12345 --samples 10 --interval-ms 300",
                "  jvmd analyze thread-dump.txt",
                "  jvmd demo deadlock",
                "  jvmd mcp",
                "",
                "Exit codes: 0 healthy, 1 degraded, 2 critical."
        },
        subcommands = {
                ListCommand.class,
                InvestigateCommand.class,
                AnalyzeCommand.class,
                DemoCommand.class,
                McpCommand.class
        }
)
public final class Main implements Runnable {

    @Override
    public void run() {
        CommandLine cmd = new CommandLine(this);
        cmd.usage(System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
