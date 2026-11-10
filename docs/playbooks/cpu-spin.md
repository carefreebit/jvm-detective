# CPU spin

**Typical pager message:** "payment-api CPU at 780% of 800% for 12 minutes, p99 4.8s, heap flat at 40%, no GC pressure"

## What is actually happening

One or more threads are RUNNABLE and burning user-space CPU in a loop that never blocks. Each spinning thread consumes a core, so the JVM still looks "busy" while request throughput falls. The JIT compiles the loop but cannot remove work that has side effects, and a single dump can catch any thread mid-method. The signal is repetition: the same stack, frame for frame, in every sample. Flat heap and low GC time rule out memory as the cause; a lock convoy shows BLOCKED threads and low CPU instead.

## Confirm it in 60 seconds

```
top -H -p 4321 -b -n 1 | head -20
printf '%x\n' 4387        # Linux TID from top -H -> the nid= value in the dump
jcmd 4321 Thread.print -l | grep -B 2 -A 20 "nid=0x1123"
jvmd investigate 4321 --samples 6 --interval-ms 400
```

On Windows `top -H` does not exist. Let jvmd's six samples do the per-thread attribution, or use Task Manager > Details > CPU per thread; `jcmd.exe <pid> Thread.print -l` is the same command.

The report names the loop directly:

> 8 thread(s) on-CPU in 6/6 samples
> top frame: com.acme.pricing.RateTable.rehash(RateTable.java:214)
> CPU on those threads: 41,220 ms (cumulative)

The matching dump fragment looks like:

> "http-nio-8080-exec-17" #47 nid=0x1123 runnable [0x00007f...]
>    java.lang.Thread.State: RUNNABLE
>         at com.acme.pricing.RateTable.rehash(RateTable.java:214)

## Read the evidence

- `RUNNABLE` means the thread is on-CPU or in a native call. It does not by itself mean it is spinning; every request thread is briefly RUNNABLE.
- jvmd only reports a hotspot when the same fingerprint appears in at least 60% of samples (4 of 6). That is a measured loop, not a lucky snapshot. A single `jvmd analyze` of a pasted dump treats a hotspot as a candidate only.
- Frame-for-frame repetition matters. If the top frame changes between samples (`read`, `write`, `park`), the thread is passing through work; if it never leaves `RateTable.rehash`, the loop is unbounded.
- Beware native frames. `Unsafe.park`, `epollWait`, `readBytes` and `SocketInputStream.read` can appear RUNNABLE while consuming no CPU. Check the `TIME+` column from `top -H`: it must be climbing for that TID.
- `BLOCKED` is not CPU. Many BLOCKED threads point to [lock contention](./lock-contention.md). High CPU on `GC Thread` or `G1 Conc` points to [memory pressure](./memory-pressure.md), not application code.
- The `cpu=...ms` field in the thread header is cumulative since thread start, not a rate. Compare it across two samples if you need a rate.
- If `top -H` spreads CPU across many `http-nio-*` threads, suspect allocation or a shared hot path; if it concentrates in two or three TIDs, the stack names the loop.
- A single `jvmd analyze` of a pasted dump cannot prove a loop. Re-run `jvmd investigate` against the live process before changing code.

## Fix it now

1. Profile before editing code. Start JFR for 60 seconds: `jcmd 4321 JFR.start duration=60s filename=/tmp/hot.jfr`. For a flame graph, use async-profiler: `./profiler.sh -d 30 -e cpu -f /tmp/cpu.html 4321`. The stack in the report tells you where to look; the profile tells you why.
2. Stop the bleeding: roll back the deploy that introduced the loop, turn off the feature flag, or shed load and scale out. If the loop is a batch job, stop that job rather than the service. Adding replicas helps only while the work is partitionable.
3. Fix the code: replace busy-waiting (`while (!ready) {}`) with a `CountDownLatch`, `BlockingQueue` or `LockSupport.parkNanos` plus a deadline. Bound every retry loop and every batch chunk, and cap the executor that runs it.

## Prevent the next one

- Keep a CPU alert per pod with thread-level attribution, and make the runbook start with `top -H` plus `jvmd investigate`, not a restart.
- Run JFR in production with a low-overhead rolling configuration (`-XX:StartFlightRecording=...`), so the next spin already has a profile.
- Add a load test at 2x peak that asserts p99 and CPU, and a review rule that rejects unbounded `while` loops and `Thread.onSpinWait` without a deadline.
- Cap batch concurrency with a dedicated small pool. A batch job sharing the request pool will always win the CPU fight at the wrong time.
- Apply backpressure instead of queueing: a bounded queue with `CallerRunsPolicy`, or a fast 429, beats an unbounded backlog that turns a slow consumer into an outage.

## Related

- [Lock contention](./lock-contention.md)
- [Memory pressure](./memory-pressure.md)
- [Triage](./triage.md)
