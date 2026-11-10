package io.github.carefreebit.jvmd.cli;

import io.github.carefreebit.jvmd.mcp.McpServer;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** {@code jvmd mcp} — expose the same engine to any MCP client over stdio. */
@Command(name = "mcp", description = "Run a Model Context Protocol server on stdio (for Claude Desktop, Cursor, Cline, etc.).", mixinStandardHelpOptions = true)
final class McpCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        try {
            new McpServer().serve();
            return 0;
        } catch (Exception e) {
            System.err.println("MCP server failed: " + e.getMessage());
            return 70;
        }
    }
}
