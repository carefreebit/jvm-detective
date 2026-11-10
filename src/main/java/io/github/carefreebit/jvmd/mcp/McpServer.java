package io.github.carefreebit.jvmd.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.carefreebit.jvmd.analyze.AnalysisResult;
import io.github.carefreebit.jvmd.analyze.Analyzer;
import io.github.carefreebit.jvmd.collect.Collector;
import io.github.carefreebit.jvmd.collect.Jcmd;
import io.github.carefreebit.jvmd.model.HeapInfo;
import io.github.carefreebit.jvmd.model.Snapshot;
import io.github.carefreebit.jvmd.model.ThreadDump;
import io.github.carefreebit.jvmd.model.VmInfo;
import io.github.carefreebit.jvmd.parse.ThreadDumpParser;
import io.github.carefreebit.jvmd.report.MarkdownReport;
import io.github.carefreebit.jvmd.report.ReportWriter;
import io.github.carefreebit.jvmd.util.BuildInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Minimal Model Context Protocol server over stdio (newline-delimited JSON-RPC 2.0).
 *
 * <p>No SDK dependency on purpose: the transport is small, and keeping the jar dependency-free
 * means it drops into any MCP client without a classpath ceremony.</p>
 */
public final class McpServer {

    private static final String PROTOCOL = "2024-11-05";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

    public void serve() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            try {
                handle(MAPPER.readTree(line));
            } catch (Exception e) {
                log("bad request: " + e.getMessage());
            }
        }
    }

    private void handle(JsonNode request) {
        String method = request.path("method").asText("");
        JsonNode id = request.get("id");
        boolean notification = id == null || id.isNull();

        switch (method) {
            case "initialize" -> respond(id, initialize(request.path("params")));
            case "notifications/initialized", "notifications/cancelled" -> {
                // no response for notifications
            }
            case "ping" -> respond(id, MAPPER.createObjectNode());
            case "tools/list" -> respond(id, tools());
            case "tools/call" -> respond(id, callTool(request.path("params")));
            default -> {
                if (!notification) {
                    error(id, -32601, "Method not found: " + method);
                }
            }
        }
    }

    private ObjectNode initialize(JsonNode params) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", params.path("protocolVersion").asText(PROTOCOL));
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "jvm-detective");
        info.put("version", BuildInfo.VERSION);
        result.put("instructions",
                "JVM Detective answers 'why is this Java process slow or stuck?'. "
                        + "Call jvm_list_processes first, then jvm_investigate with the pid. "
                        + "Prefer 6-10 samples at 300-500ms for CPU/lock problems. "
                        + "The verdict is one of CRITICAL, DEGRADED, WATCH, HEALTHY; findings are ranked by severity.");
        return result;
    }

    private ObjectNode tools() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        tools.add(tool("jvm_list_processes",
                "List JVMs on this machine that jcmd can attach to. Use this to find the pid.",
                """
                        {"type":"object","properties":{}}"""));

        tools.add(tool("jvm_investigate",
                "Sample a live JVM (jcmd thread dumps + GC/heap/VM facts), detect deadlocks, lock pile-ups, "
                        + "CPU spins, thread leaks and pool starvation, and return a ranked Markdown incident report. "
                        + "Also writes report.html/json under ./jvmd-reports.",
                """
                        {"type":"object","properties":{
                          "pid":{"type":"integer","description":"Target JVM process id"},
                          "samples":{"type":"integer","minimum":1,"maximum":30,"default":6,
                                     "description":"Thread-dump samples; more samples distinguish a CPU spin from a one-off burst"},
                          "interval_ms":{"type":"integer","minimum":0,"maximum":5000,"default":400,
                                        "description":"Delay between samples"}
                        },"required":["pid"]}"""));

        tools.add(tool("jvm_analyze_thread_dump",
                "Analyse an existing thread-dump text (from `jcmd <pid> Thread.print -l` or `jstack -l`). "
                        + "Use this when attaching is impossible (containers, prod lockdown, a dump pasted in chat).",
                """
                        {"type":"object","properties":{
                          "dump":{"type":"string","description":"Full thread-dump text"},
                          "name":{"type":"string","description":"Optional label, e.g. the file name"}
                        },"required":["dump"]}"""));

        return result;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        try {
            return switch (name) {
                case "jvm_list_processes" -> text(listProcesses());
                case "jvm_investigate" -> text(investigate(args));
                case "jvm_analyze_thread_dump" -> text(analyzeDump(args));
                default -> toolError("Unknown tool: " + name);
            };
        } catch (Exception e) {
            return toolError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String listProcesses() {
        List<Jcmd.JvmProcess> processes = Jcmd.processes();
        if (processes.isEmpty()) {
            return "No attachable JVMs. jcmd only sees processes owned by the same user.";
        }
        StringBuilder b = new StringBuilder("| PID | Main class | Command |\n|---:|---|---|\n");
        for (Jcmd.JvmProcess p : processes) {
            b.append("| ").append(p.pid()).append(" | `").append(p.mainClass()).append("` | `")
                    .append(trim(p.command(), 120)).append("` |\n");
        }
        return b.toString();
    }

    private String investigate(JsonNode args) throws IOException {
        long pid = args.path("pid").asLong(0);
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be a positive JVM process id");
        }
        int samples = clamp(args.path("samples").asInt(6), 1, 30);
        long interval = clamp(args.path("interval_ms").asLong(400), 0, 5000);

        Snapshot snapshot = Collector.collect(pid, samples, interval, message -> log("investigate " + message));
        AnalysisResult result = new Analyzer().analyze(snapshot);
        String markdown = MarkdownReport.render(snapshot, result);
        try {
            ReportWriter.Output output = ReportWriter.write(snapshot, result, Path.of("jvmd-reports"));
            markdown += "\n\nFull report: `" + output.html().toAbsolutePath() + "`\n";
        } catch (IOException e) {
            log("could not write report: " + e.getMessage());
        }
        return markdown;
    }

    private String analyzeDump(JsonNode args) {
        String text = args.path("dump").asText("");
        if (text.isBlank()) {
            throw new IllegalArgumentException("dump must contain the thread-dump text");
        }
        String name = args.path("name").asText("thread-dump.txt");
        ThreadDump dump = ThreadDumpParser.parse(text);
        if (dump.size() == 0) {
            throw new IllegalArgumentException("no Java threads found; expected jcmd Thread.print -l or jstack -l output");
        }
        VmInfo vm = new VmInfo(0, name, "", "unknown", "unknown", System.getProperty("os.name"),
                System.getProperty("os.arch"), -1, Runtime.getRuntime().availableProcessors(), Map.of(), Map.of());
        Snapshot snapshot = new Snapshot(vm, HeapInfo.UNKNOWN, List.of(dump), Instant.now(),
                Map.of("thread_dump", text));
        return MarkdownReport.render(snapshot, new Analyzer().analyze(snapshot));
    }

    // ---------------------------------------------------------------- plumbing

    private static ObjectNode tool(String name, String description, String schema) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        try {
            tool.set("inputSchema", MAPPER.readTree(schema));
        } catch (IOException e) {
            tool.putObject("inputSchema").put("type", "object");
        }
        return tool;
    }

    private ObjectNode text(String text) {
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", false);
        return result;
    }

    private ObjectNode toolError(String message) {
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", "Error: " + message);
        result.put("isError", true);
        return result;
    }

    private void respond(JsonNode id, JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        send(response);
    }

    private void error(JsonNode id, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        ObjectNode err = response.putObject("error");
        err.put("code", code);
        err.put("message", message);
        send(response);
    }

    private void send(ObjectNode node) {
        try {
            out.println(MAPPER.writeValueAsString(node));
            out.flush();
        } catch (Exception e) {
            log("cannot write response: " + e.getMessage());
        }
    }

    private static void log(String message) {
        System.err.println("[jvmd-mcp] " + message);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
