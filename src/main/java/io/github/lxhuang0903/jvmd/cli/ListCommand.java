package io.github.lxhuang0903.jvmd.cli;

import io.github.lxhuang0903.jvmd.collect.Jcmd;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

/** {@code jvmd list} — what can I attach to? */
@Command(name = "list", description = "List JVMs on this machine that jcmd can attach to.", mixinStandardHelpOptions = true)
final class ListCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        List<Jcmd.JvmProcess> processes = Jcmd.processes();
        if (processes.isEmpty()) {
            System.out.println("No attachable JVMs found.");
            System.out.println("jcmd can only see JVMs owned by the same user (and, on some systems, not across containers).");
            return 0;
        }
        System.out.printf("%-8s %-28s %s%n", "PID", "MAIN CLASS", "COMMAND");
        for (Jcmd.JvmProcess process : processes) {
            System.out.printf("%-8d %-28s %s%n", process.pid(),
                    trim(process.mainClass(), 28), trim(process.command(), 92));
        }
        System.out.println();
        System.out.println("Next: jvmd investigate <pid>");
        return 0;
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
