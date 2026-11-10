package io.github.carefreebit.jvmd.cli;

import picocli.CommandLine.Option;

import java.nio.file.Path;

/** Options shared by every command that collects or renders a report. */
final class CommonOptions {

    @Option(names = {"-s", "--samples"}, defaultValue = "6",
            description = "Number of thread-dump samples (default: ${DEFAULT-VALUE}).")
    int samples;

    @Option(names = {"-i", "--interval-ms"}, defaultValue = "500",
            description = "Delay between samples in milliseconds (default: ${DEFAULT-VALUE}).")
    long intervalMs;

    @Option(names = {"-o", "--out"}, defaultValue = "jvmd-reports",
            description = "Directory for report folders (default: ${DEFAULT-VALUE}).")
    Path out;

    @Option(names = "--print", defaultValue = "none",
            description = "Also print 'md' or 'json' to stdout.")
    String print;
}
