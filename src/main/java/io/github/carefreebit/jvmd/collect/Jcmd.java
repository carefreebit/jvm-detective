package io.github.carefreebit.jvmd.collect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Thin, timeout-safe wrapper around the JDK's {@code jcmd} tool. */
public final class Jcmd {

    private static final Pattern PROCESS_LINE = Pattern.compile("^(\\d+)\\s+(.+)$");
    private static final long DEFAULT_TIMEOUT_SECONDS = 25;

    private Jcmd() {
    }

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }

        public String errorText() {
            if (stderr != null && !stderr.isBlank()) {
                return stderr.trim();
            }
            return stdout == null ? "" : stdout.trim();
        }
    }

    public record JvmProcess(long pid, String command) {
        public String mainClass() {
            if (command == null || command.isBlank()) {
                return "unknown";
            }
            String first = command.trim().split("\\s+")[0];
            int slash = first.lastIndexOf('/');
            return slash >= 0 ? first.substring(slash + 1) : first;
        }
    }

    public static Path executable() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path candidate = Path.of(home, "bin", windows ? "jcmd.exe" : "jcmd");
        if (Files.isExecutable(candidate)) {
            return candidate;
        }
        return Path.of(windows ? "jcmd.exe" : "jcmd");
    }

    public static Result run(long pid, String... args) {
        List<String> command = new ArrayList<>();
        command.add(executable().toString());
        command.add(Long.toString(pid));
        command.addAll(List.of(args));
        return exec(command, DEFAULT_TIMEOUT_SECONDS);
    }

    public static Result list() {
        return exec(List.of(executable().toString(), "-l"), DEFAULT_TIMEOUT_SECONDS);
    }

    public static List<JvmProcess> processes() {
        Result result = list();
        if (!result.ok()) {
            throw new IllegalStateException("`jcmd -l` failed: " + result.errorText()
                    + "\nMake sure you run JVM Detective with a full JDK (not a JRE) and that the target JVM belongs to you.");
        }
        List<JvmProcess> list = new ArrayList<>();
        long self = ProcessHandle.current().pid();
        for (String line : result.stdout().split("\\R")) {
            Matcher m = PROCESS_LINE.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            long pid = Long.parseLong(m.group(1));
            String command = m.group(2).trim();
            if (pid == self || command.contains("sun.tools.jcmd.JCmd") || command.contains("jdk.jcmd")
                    || command.equals("Dead")) {
                continue;
            }
            list.add(new JvmProcess(pid, command));
        }
        return list;
    }

    static Result exec(List<String> command, long timeoutSeconds) {
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            process = pb.start();
        } catch (IOException e) {
            return new Result(-1, "", "Cannot execute " + command.get(0) + ": " + e.getMessage()
                    + "\nInstall a JDK 17+ and make sure `jcmd` is on PATH.");
        }
        // A dedicated daemon thread keeps stderr draining while stdout is read inline. Using the
        // common ForkJoinPool here would park one of its workers inside a blocking read and make
        // it look like an application hotspot when jvmd samples itself in demo mode.
        String[] err = {""};
        Thread errReader = new Thread(() -> err[0] = read(process.getErrorStream()), "jvmd-stderr-reader");
        errReader.setDaemon(true);
        errReader.start();
        String stdout = read(process.getInputStream());
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, stdout, "jcmd timed out after " + timeoutSeconds + "s");
            }
            errReader.join(2000);
            return new Result(process.exitValue(), stdout, err[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, stdout, "interrupted");
        } catch (Exception e) {
            process.destroyForcibly();
            return new Result(-1, stdout, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
