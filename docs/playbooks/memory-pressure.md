# Memory pressure

**Typical pager message:** "catalog-api: heap at 96% since the 14:00 deploy, Full GC every 45s, p99 6s, one pod OOMKilled at 3.9 GiB of its 4 GiB limit"

## What is actually happening

The heap holds live objects plus garbage. The collector reclaims garbage, but it cannot reclaim objects that are still reachable, so as the live set approaches `-Xmx` the JVM spends a growing share of CPU on GC and pauses lengthen. This is different from Metaspace, which stores class metadata in native memory: a classloader leak grows Metaspace and ends in `OutOfMemoryError: Metaspace`, not a heap OOM. In a container the JVM must also fit Metaspace, thread stacks, direct buffers and code cache inside the cgroup limit, which is why a heap that looks fine can still get the process OOM-killed.

## Confirm it in 60 seconds

```
jcmd 4321 GC.heap_info
jcmd 4321 VM.flags | tr ' ' '\n' | grep -E "Xmx|Xms|MaxRAM|UseG1|UseZGC|HeapDump"
jcmd 4321 GC.heap_dump /tmp/catalog-$(date +%s).hprof
jvmd investigate 4321 --samples 6 --interval-ms 500
```

On Windows use `jcmd.exe` and a path such as `%TEMP%\catalog.hprof`. `GC.heap_dump` stops the world while it writes, so expect a pause proportional to heap size.

Evidence that the live set, not a transient spike, is the problem:

> heap used: 3.7G / 4.0G (92.5%)
> collector: G1 Young Generation, G1 Old Generation
> Metaspace: 214M committed, 1.0G reserved

jvmd reports the same ratio as `heap used 3.7 GB / 4.0 GB (92%)`, severity HIGH at 90% or above. Dump to a volume with room for the whole heap; a dump is written at bulk speed and will fill a small disk.

## Read the evidence

- Ratio alone is not a diagnosis. A cache filling 80% and staying flat is healthy. What matters is the value after a Full GC: if it stays above 85%, the live set is too large for the heap, which is either a leak or an undersized `-Xmx`.
- Young GCs with short pauses and high frequency mean allocation pressure, not necessarily a leak. Frequent `Pause Full` events with long pauses mean the live set is pressing on the heap.
- Read the OOM message. `Java heap space` is heap; `Metaspace` is class metadata; `Direct buffer memory` is off-heap; `unable to create native thread` is thread stacks. A container OOM kill (exit 137, `OOMKilled`) often leaves no Java OOM at all.
- Check `MaxRAMPercentage` against the container limit. The default is 25% of the limit; if the limit is 4 GiB and the heap is 1 GiB, the application may GC constantly with room to spare, while a heap set to 95% of the limit leaves nothing for off-heap memory.
- Allocation pressure and a leak need different fixes. Sample `GC.heap_info` twice, five minutes apart, including one Full GC: a stable live set is pressure; steady growth is a leak.
- `jcmd VM.native_memory summary` accounts for Metaspace, thread stacks, code cache and direct buffers, but only if the JVM was started with `-XX:NativeMemoryTracking=summary`. It explains the gap between heap and container RSS.
- `jstat -gcutil 4321 1000 10` gives the same trend cheaply when `jcmd` is unavailable; `GC.heap_info` remains the one-line summary to paste into the ticket.
- G1 and ZGC report pauses differently. Do not compare their numbers directly, and never switch collector during an incident.

## Fix it now

1. Capture a heap dump before restarting if the process is stable enough: `jcmd 4321 GC.heap_dump /tmp/catalog.hprof`. If it is already dying, rely on `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/dumps` and do not risk a long pause.
2. Recover users: roll the pod or restart the instance, and disable the deploy or feature flag that changed the retention pattern. Raise the heap only if the container has headroom: increase the limit and set `-XX:MaxRAMPercentage=70`, not 90, so Metaspace, stacks and direct buffers still fit.
3. Fix the retention: open the dump in Eclipse MAT or use the report's findings, walk the dominator tree, and find what holds the biggest retained set. Typical causes are an unbounded `HashMap` cache, a static collection, a `ThreadLocal` on a pooled thread, or a listener that is never removed.

## Prevent the next one

- Always run with `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/dumps` and `-XX:+ExitOnOutOfMemoryError`; a dump is worth more than a restart.
- Alert on heap after old GC above 80% for ten minutes, on GC pause p99, and on container working set versus limit — not just on the heap ratio.
- Budget caches explicitly: Caffeine `maximumSize`/`maximumWeight` with `expireAfter`, sized in bytes, and a test that asserts the budget.
- Keep the heap at 70–75% of the container limit and verify with a load test using production-sized data; small heaps with big caches fail at the worst time.
- Set `-XX:MaxMetaspaceSize` and test a redeploy in staging, so a classloader leak shows up as a bounded failure rather than a slow container kill.
- Set `-Xms` equal to `-Xmx` so the heap does not grow into the container limit during a burst and steal room from off-heap memory.
- Watch RSS and the container working set, not just the heap ratio. A JVM at 70% heap can still be killed by Metaspace, stacks or direct buffers.
- If the volume is too small for a dump, collect `jcmd GC.class_histogram` first. It is smaller and often identifies the dominant class, though it also pauses the JVM.

## Related

- [Thread leak](./thread-leak.md)
- [CPU spin](./cpu-spin.md)
- [Triage](./triage.md)
