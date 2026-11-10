package io.github.carefreebit.jvmd.collect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Collector parsing")
class CollectorTest {

    @Test
    @DisplayName("VM.uptime ignores the pid header and reads the unit")
    void parsesUptime() {
        assertEquals(30_844, Collector.parseUptime("12452:\n30.844 s\n"));
        assertEquals(1_500, Collector.parseUptime("1.500 s"));
        assertEquals(250, Collector.parseUptime("250 ms"));
        assertEquals(-1, Collector.parseUptime(""));
    }

    @Test
    @DisplayName("system properties are unescaped, including Windows paths")
    void unescapesProperties() {
        String text = "java.version=21.0.6\n"
                + "java.home=C\\:\\\\Program Files\\\\Java\\\\jdk-21\n"
                + "sun.java.command=com.acme.Main --flag=1\n"
                + "line.separator=\\n\n";
        Map<String, String> props = Collector.parseProperties(text);

        assertEquals("21.0.6", props.get("java.version"));
        assertEquals("C:\\Program Files\\Java\\jdk-21", props.get("java.home"));
        assertEquals("com.acme.Main --flag=1", props.get("sun.java.command"));
        assertEquals("\n", props.get("line.separator"));
    }

    @Test
    @DisplayName("flags keep -XX:+/- and size values in the shape the analyzer expects")
    void parsesFlags() {
        Map<String, String> flags = Collector.parseFlags(
                "-XX:+UseG1GC -XX:-UseBiasedLocking -XX:MaxHeapSize=2147483648 -Xmx2g -Xss1m -XX:+HeapDumpOnOutOfMemoryError");

        assertEquals("true", flags.get("UseG1GC"));
        assertEquals("false", flags.get("UseBiasedLocking"));
        assertEquals("2147483648", flags.get("MaxHeapSize"));
        assertEquals("2g", flags.get("Xmx"));
        assertEquals("1m", flags.get("Xss"));
        assertTrue(flags.containsKey("HeapDumpOnOutOfMemoryError"));
    }

    @Test
    @DisplayName("processor count falls back to sun.cpu.count")
    void parsesProcessors() {
        assertEquals(12, Collector.parseProcessors(
                "active_processor_count = 12", Map.of()));
        assertEquals(8, Collector.parseProcessors(
                "", Map.of("sun.cpu.count", "8")));
    }
}
