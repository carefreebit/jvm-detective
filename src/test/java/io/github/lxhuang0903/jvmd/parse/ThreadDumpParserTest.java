package io.github.lxhuang0903.jvmd.parse;

import io.github.lxhuang0903.jvmd.model.LockRef;
import io.github.lxhuang0903.jvmd.model.StackFrame;
import io.github.lxhuang0903.jvmd.model.ThreadDump;
import io.github.lxhuang0903.jvmd.model.ThreadInfo;
import io.github.lxhuang0903.jvmd.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ThreadDumpParser")
class ThreadDumpParserTest {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    @DisplayName("drops VM-internal threads from the application thread list")
    void excludesVmThreads() {
        ThreadDump dump = TestFixtures.threadDump("deadlock.txt");

        assertEquals(10, dump.size(), "14 headers minus 4 VM threads");
        assertNull(dump.byName("VM Thread"));
        assertNull(dump.byName("VM Periodic Task Thread"));
        assertNull(dump.byName("GC Thread#0"));
        assertNull(dump.byName("G1 Conc#0"));
        assertNotNull(dump.byName("order-worker-1"));
        assertTrue(dump.raw().contains("\"VM Thread\" os_prio=2"), "VM threads stay in the raw capture");
    }

    @Test
    @DisplayName("de-duplicates the repeated deadlock stacks")
    void collectsDeadlockedThreadsWithoutDoubleCounting() {
        ThreadDump dump = TestFixtures.threadDump("deadlock.txt");

        assertEquals(List.of("order-worker-1", "order-worker-2"), dump.deadlockedThreads());
        // The capture names each thread in the header, the deadlock summary and the stack section.
        assertTrue(occurrences(dump.raw(), "\"order-worker-1\"") >= 3,
                "fixture must repeat each deadlock name to prove de-duplication");
        assertEquals(1, dump.threads().stream().filter(t -> t.name().equals("order-worker-1")).count());
        assertEquals(1, dump.threads().stream().filter(t -> t.name().equals("order-worker-2")).count());
    }

    @Test
    @DisplayName("captures BLOCKED state, cyclic monitors and stack frames")
    void parsesBlockedThreadsAndLockGraph() {
        ThreadDump dump = TestFixtures.threadDump("deadlock.txt");
        ThreadInfo worker1 = dump.byName("order-worker-1");
        ThreadInfo worker2 = dump.byName("order-worker-2");

        assertNotNull(worker1);
        assertNotNull(worker2);
        assertEquals("BLOCKED", worker1.state());
        assertEquals("BLOCKED", worker2.state());
        assertTrue(worker1.daemon());
        assertEquals(29, worker1.id());
        assertEquals(5, worker1.priority());

        LockRef held = worker1.waitingToLock();
        assertNotNull(held, "worker-1 is waiting for a monitor");
        assertEquals("0x00000000d5e1a2b8", held.id());
        assertEquals("java.lang.Object", held.className());

        // A true cycle: each thread waits for the monitor the other one holds.
        assertEquals(worker2.waitingToLock().id(), worker1.locked().get(0).id());
        assertEquals(worker1.waitingToLock().id(), worker2.locked().get(0).id());
        assertEquals("com.acme.orders.Account", worker2.waitingToLock().className());

        StackFrame top = worker1.topFrame();
        assertNotNull(top);
        assertEquals("com.acme.orders.Ledger", top.className());
        assertEquals("transfer", top.methodName());
        assertEquals("Ledger.java", top.fileName());
        assertEquals(88, top.line());
        assertFalse(top.isNative());
        assertTrue(top.display().startsWith("Ledger.transfer(Ledger.java:88)"));
    }

    @Test
    @DisplayName("reads the capture timestamp in the local zone")
    void parsesTimestamp() {
        ThreadDump dump = TestFixtures.threadDump("deadlock.txt");

        assertNotNull(dump.timestamp());
        assertEquals("2026-11-10 22:41:51",
                LOCAL.withZone(ZoneId.systemDefault()).format(dump.timestamp()));
    }

    @Test
    @DisplayName("parses a contended monitor with twelve waiters")
    void parsesLockContention() {
        ThreadDump dump = TestFixtures.threadDump("lock-contention.txt");

        assertTrue(dump.deadlockedThreads().isEmpty());
        Map<String, List<ThreadInfo>> waiters = dump.waitersByLock();
        assertEquals(1, waiters.size());
        List<ThreadInfo> queued = waiters.get("0x00000000ab12cd34");
        assertNotNull(queued);
        assertEquals(12, queued.size());
        assertTrue(queued.stream().allMatch(t -> "BLOCKED".equals(t.state())));
        assertEquals(12L, dump.count("BLOCKED"));

        ThreadInfo owner = dump.byName("checkout-owner");
        assertNotNull(owner);
        assertEquals("RUNNABLE", owner.state());
        assertEquals("0x00000000ab12cd34", owner.locked().get(0).id());
        assertEquals("com.acme.checkout.PriceTable", owner.locked().get(0).className());
    }

    @Test
    @DisplayName("parses the idle pool: parked threads, native frames and pool groups")
    void parsesIdlePool() {
        ThreadDump dump = TestFixtures.threadDump("idle.txt");

        assertEquals(14, dump.size());
        ThreadInfo idle = dump.byName("http-nio-8080-exec-1");
        assertNotNull(idle);
        assertEquals("WAITING", idle.state());
        assertTrue(idle.isIdle(), "parked pool thread is idle");
        StackFrame top = idle.topFrame();
        assertNotNull(top);
        assertEquals("jdk.internal.misc.Unsafe", top.className());
        assertTrue(top.isNative());

        ThreadInfo sleeper = dump.byName("scheduler-1");
        assertNotNull(sleeper);
        assertEquals("TIMED_WAITING", sleeper.state());
        assertTrue(sleeper.isIdle());

        assertEquals(8, dump.threadGroups().get("http-nio-8080-exec").size(),
                "exec-N threads collapse into one pool group");
        assertFalse(dump.threadGroups().containsKey("http-nio-8080-exec-1"));
    }

    @Test
    @DisplayName("survives a dump without a deadlock section, and empty input")
    void parsesDumpWithoutDeadlockSection() {
        String text = "4242:\n"
                + "2026-11-10 22:45:00\n"
                + "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.6+9-LTS-2320 mixed mode, sharing):\n\n"
                + "\"main\" #1 [1234] prio=5 os_prio=0 cpu=1.00ms elapsed=2.00s tid=0x1 nid=1234 runnable  [0x2]\n"
                + "   java.lang.Thread.State: RUNNABLE\n"
                + "    at com.acme.App.main(App.java:10)\n";

        ThreadDump dump = ThreadDumpParser.parse(text);
        assertEquals(1, dump.size());
        assertTrue(dump.deadlockedThreads().isEmpty());
        assertEquals("RUNNABLE", dump.byName("main").state());
        assertEquals(10, dump.byName("main").topFrame().line());

        ThreadDump empty = ThreadDumpParser.parse("");
        assertEquals(0, empty.size());
        assertTrue(empty.deadlockedThreads().isEmpty());
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
