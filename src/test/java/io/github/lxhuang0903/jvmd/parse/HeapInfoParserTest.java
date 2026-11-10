package io.github.lxhuang0903.jvmd.parse;

import io.github.lxhuang0903.jvmd.model.HeapInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HeapInfoParser")
class HeapInfoParserTest {

    private static final long MAX_HEAP = 2L * 1024 * 1024 * 1024;

    /** Real JDK 21 G1 output shape. */
    private static final String G1 = ""
            + " garbage-first heap   total 2097152K, used 524288K [0x0000000700000000, 0x0000000800000000)\n"
            + "  region size 1024K, 512 young (524288K), 0 survivors (0K)\n"
            + " Metaspace       used 12345K, committed 13000K, reserved 1056768K\n"
            + "  class space    used 1200K, committed 1400K, reserved 1048576K\n";

    /** Real JDK 21 Parallel output shape: PSYoungGen is the first "total/used" line. */
    private static final String PARALLEL = ""
            + " PSYoungGen      total 306176K, used 12345K [0x0000000700000000, 0x00000007b0000000, 0x0000000800000000)\n"
            + "  eden space 131072K, 12% used [0x0000000700000000,0x0000000700c1f000,0x0000000708000000)\n"
            + "  from space 175104K, 0% used [0x0000000708000000,0x0000000708000000,0x00000007b2b00000)\n"
            + "  to   space 175104K, 0% used [0x00000007b2b00000,0x00000007b2b00000,0x0000000800000000)\n"
            + " ParOldGen       total 348160K, used 0K [0x00000007b2b00000, 0x00000007c0000000, 0x00000007c0000000)\n"
            + "  object space 348160K, 0% used [0x00000007b2b00000,0x00000007b2b00000,0x00000007c0000000)\n"
            + " Metaspace       used 5000K, committed 6000K, reserved 1056768K\n"
            + "  class space    used 700K, committed 800K, reserved 1048576K\n";

    @Test
    @DisplayName("parses G1 heap, ratio and metaspace")
    void parsesG1HeapInfo() {
        HeapInfo heap = HeapInfoParser.parse(G1, MAX_HEAP);

        assertEquals("garbage-first heap", heap.collector());
        assertEquals(524288L * 1024, heap.usedBytes());
        assertEquals(2097152L * 1024, heap.committedBytes(), "committed comes from the heap total");
        assertEquals(MAX_HEAP, heap.maxBytes());
        assertEquals(0.25, heap.usageRatio(), 1e-9);
        assertEquals(1.0, heap.committedRatio(), 1e-9);
        assertTrue(heap.known());

        assertEquals(12345L * 1024, heap.metaspaceUsedBytes());
        assertEquals(13000L * 1024, heap.metaspaceCommittedBytes());
        assertEquals(1056768L * 1024, heap.metaspaceReservedBytes());
    }

    @Test
    @DisplayName("falls back to the heap total when the max is unknown")
    void maxFallsBackToTotal() {
        HeapInfo heap = HeapInfoParser.parse(G1, 0);

        assertEquals(2097152L * 1024, heap.maxBytes());
        assertEquals(0.25, heap.usageRatio(), 1e-9);
        assertTrue(heap.known());
    }

    @Test
    @DisplayName("uses the first total/used line and ignores eden/old details")
    void parsesParallelHeapInfo() {
        HeapInfo heap = HeapInfoParser.parse(PARALLEL, 0);

        assertEquals("PSYoungGen", heap.collector());
        assertEquals(306176L * 1024, heap.committedBytes());
        assertEquals(12345L * 1024, heap.usedBytes());
        assertEquals(306176L * 1024, heap.maxBytes());
        assertEquals(12345.0 / 306176.0, heap.usageRatio(), 1e-9);
        assertEquals(5000L * 1024, heap.metaspaceUsedBytes());
    }

    @Test
    @DisplayName("caps usage at 100% when the reported max is smaller than used")
    void capsUsageRatioAtOne() {
        HeapInfo heap = HeapInfoParser.parse(G1, 256L * 1024 * 1024);

        assertEquals(1.0, heap.usageRatio());
        assertEquals(1.0, heap.committedRatio());
    }

    @Test
    @DisplayName("returns UNKNOWN for blank or unrecognised input")
    void unknownWhenUnparseable() {
        assertEquals(HeapInfo.UNKNOWN, HeapInfoParser.parse("", 0));
        assertEquals(HeapInfo.UNKNOWN, HeapInfoParser.parse("   \n\t ", 0));
        assertEquals(HeapInfo.UNKNOWN, HeapInfoParser.parse("command not recognized", 0));
        assertEquals(HeapInfo.UNKNOWN, HeapInfoParser.parse(null, 0));

        assertFalse(HeapInfoParser.parse("", 0).known());
        assertEquals(-1.0, HeapInfoParser.parse("", 0).usageRatio());
    }
}
