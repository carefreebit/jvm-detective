# Thread leak

**Typical pager message:** "report-api: thread count 1,850 and climbing ~40/min, RSS 3.4 GB, one pod died with `unable to create native thread`"

## What is actually happening

Every live thread is a GC root with its own native stack (about 1 MB reserved by default), kernel task structures and thread-local map. Threads are not collected while they are alive, so a slow leak raises memory and scheduling cost at the same time: more context switches, longer safepoints and more roots to scan. Common causes are `new Thread` per request or per task, an executor with an unbounded queue or unbounded maximum, `ThreadLocal` values left on pooled threads, and a redeployed webapp whose classloader stays pinned. The process usually dies with `OutOfMemoryError: unable to create native thread`, not a Java heap OOM.

## Confirm it in 60 seconds

```
jvmd investigate 4321 --samples 6 --interval-ms 1000
jcmd 4321 Thread.print -l | grep '^"' | sed 's/".*//' | sort | uniq -c | sort -rn | head
jcmd 4321 Thread.print -l | grep -c '^"'
ulimit -u; cat /sys/fs/cgroup/pids.max 2>/dev/null
```

On Windows there is no `ulimit`; check the process handle count and `Get-Process -Id 4321 | Select-Object Threads`, and use `jcmd.exe`.

The report groups identical prefixes and shows which family is growing:

> total threads: 532 (largest group "order-sync": 520)
> growth during capture: +0 threads over 6 samples
> order-sync                                520 threads  e.g. order-sync-0

## Read the evidence

- Absolute count is only half the signal. A 200-thread Tomcat plus Hikari plus Netty deployment can be healthy; the shape and the slope matter more.
- Watch the largest normalised group. jvmd strips trailing digits, so `order-sync-0` through `order-sync-519` collapse into one row instead of 520 findings.
- Growth during capture is the strongest evidence. Five new threads in five seconds is a leak; a flat 520-thread batch pool is a sizing decision.
- State does not exonerate a thread. `WAITING` and `TIMED_WAITING` threads still hold stacks, thread-locals and GC roots; a leak of parked threads is the common case.
- `new Thread` per request often appears as `pool-N-thread-M` or an application prefix. A cached pool (`Executors.newCachedThreadPool`) grows without bound; a fixed pool has an unbounded queue, so the queue leaks instead of the thread count.
- `ThreadLocal` leaks are invisible in a thread dump. Take a heap dump and look for `ThreadLocalMap` entries retaining request or user objects; on redeploy, an old `WebappClassLoader` retained by a thread is a Metaspace leak, not a heap leak.
- `jcmd Thread.print` includes daemon threads, and jvmd counts them. A growing `ForkJoinPool.commonPool-worker-*` family is usually library parallelism, not a leak, but it still costs stacks and roots.
- Compare with the previous deployment's report. A jump from 180 to 900 threads after a release is more actionable than any absolute threshold.
- `pstree -p 4321` shows the process tree and confirms whether the threads belong to this JVM or to a wrapper that supervises it.

## Fix it now

1. Capture the evidence: save the dump, note the count and the top group, and find the creator with `jcmd 4321 Thread.print -l | grep -A 20 "order-sync-0"`. The stack shows whether the thread was created by a request path, a scheduler or a library.
2. Recover capacity: roll the pod or restart the instance. Disable the feature flag that creates the threads if you can identify it. Lowering `-Xss` is not a fix and can break deep frameworks; do not use it as the mitigation.
3. Replace the creator with a bounded pool: `new ThreadPoolExecutor(8, 32, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200), namedFactory, new ThreadPoolExecutor.CallerRunsPolicy())`. Create it once, share it, and shut it down in a `finally` block. Clear `ThreadLocal` in `finally` (`MDC.clear()`, `ThreadLocal.remove()`).

## Prevent the next one

- Name every thread through a factory and cap every pool. Unnamed `pool-3-thread-7` threads make the next incident slower to diagnose.
- Alert on the derivative, not the absolute: `thread count grew > 100 in 10 minutes` or a group growing monotonically across three scrapes.
- Add a soak test that runs 10,000 requests and asserts the thread count returns to baseline; this catches per-request executors and missing shutdown.
- If you move to virtual threads (final in JDK 21, not available on this Java 17 baseline), remember they are cheap but not free: bound concurrency with a `Semaphore`, clear `ThreadLocal`s, and know that `synchronized` pins the carrier thread on JDK 21 (JEP 491 fixes this in JDK 24). Unbounded virtual threads can exhaust the database pool faster than platform threads did.
- Take a heap dump after a redeploy in staging and diff classloader counts; that is the only reliable check for a `WebappClassLoader` leak.
- Alert on `OutOfMemoryError: unable to create native thread` as its own signal; it is a thread-limit failure, not a heap failure, and the runbook differs.
- Never build a pool inside a request or a bean method. Create it once at startup, inject it, and shut it down with the application context.
- Give schedulers and long-lived workers their own names so a leak is attributable from a single `grep` of the dump.
- Set `-XX:+ExitOnOutOfMemoryError` so a native-thread failure restarts cleanly instead of leaving a half-dead process serving nothing.

## Related

- [Pool starvation](./pool-starvation.md)
- [Memory pressure](./memory-pressure.md)
- [Triage](./triage.md)
