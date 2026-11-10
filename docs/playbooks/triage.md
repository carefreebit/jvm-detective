# Triage

**Typical pager message:** "prod is slow, checkout latency is up across the board, nobody knows which service to look at first"

## What is actually happening

Almost every JVM incident is one saturated resource with several symptoms: CPU, memory, or threads. Blocked threads look like slow code, a full heap looks like a CPU problem because GC consumes cores, and a stalled dependency looks like a thread leak because the pool fills up. Triage is the five minutes you spend deciding which of those you have before anyone restarts anything. jvmd exists for that decision: it attaches with `jcmd`, samples the threads, and writes a report with a verdict you can paste into the incident channel.

## Confirm it in 60 seconds

```
jvmd list
jvmd investigate 4321 --samples 6 --interval-ms 500
```

`jvmd list` prints the PID, main class and command line of every JVM that `jcmd` can see, excluding jvmd itself, so you can confirm you are looking at the right process and that you own it. `investigate` collects `vm.info`, `vm.flags`, `GC.heap_info` and six thread dumps, then writes `jvmd-reports/case-<id>-<timestamp>/`.

On Windows run the same commands from PowerShell with `jcmd.exe`; the user-ownership rule still applies.

The console summary is the triage result:

>   VERDICT   CRITICAL   [CRITICAL]
>   [ CRITICAL ] Deadlock
>           2 thread(s) in a lock cycle: order-worker-1, order-worker-2
>   report    jvmd-reports/case-a1b2c3-20261110-221503/report.html

The numeric risk score appears in `report.html` and `report.json`, not on the console. The console stops at six findings; the report files contain all of them.

## Read the evidence

- The verdict follows the findings: any `CRITICAL` finding makes the case CRITICAL, a `HIGH` makes it DEGRADED, `MEDIUM` or `LOW` makes it WATCH. The risk score sums every finding (CRITICAL 75, HIGH 34, MEDIUM 14, LOW 5), adds bonuses (deadlock +15, heap at or above 85% +10, ten or more blocked threads +6), clamps at 100, and labels SEV-1 at 70 or above, SEV-2 at 45, SEV-3 at 25, otherwise SEV-4.
- Use the exit code in scripts: 0 healthy, 1 degraded, 2 critical. `jvmd analyze <dump>` runs the same analysis offline on a dump you cannot attach to; VM facts show as unknown (`pid 0`, `runtime unknown`), and a single snapshot marks CPU hotspots as candidates only.
- Read the findings in order, not the thread list. The console prints at most six findings, and each carries its evidence and a first fix; the report files contain all of them. The thread dump is for confirming, not for browsing.
- If CPU is high and one stack repeats, go to CPU spin. If threads are BLOCKED behind one lock id, go to lock contention or deadlock. If threads are waiting on `getConnection`, it is pool starvation. If they sit in socket reads or `Future.get`, it is a downstream stall. If the heap is above 90%, go to memory pressure.
- Sampling has blind spots: six dumps over about three seconds miss a spike that happens every thirty seconds, and a thread that only occasionally holds a lock may not appear. Re-run with `-s 12 -i 1000` before concluding "nothing found". A single dump is one photograph; six are a short film.
- If `jcmd` cannot attach, the user or container is the reason: jvmd can only see JVMs owned by the same OS user. Use `jvmd analyze` on a dump copied out of the container, or run the tool inside the pod.
- The thread table in the HTML report is filterable by name and state. Filter to the suspect pool and read the states in aggregate before reading individual stacks.
- A HEALTHY or WATCH verdict is not an all-clear. Read the baseline finding for the runtime facts and compare it with the last known-good case for that service.
- `jvmd mcp` exposes the same analysis over stdio for agent integrations. It is useful for automation, not for the on-call path.
- Work in a fixed order: is the process alive and is CPU pegged; is the heap above 90% after old GC; then read the thread-state distribution. That rules out the cheapest causes before you touch the expensive ones.

## Fix it now

1. Classify in five minutes: run `jvmd list`, then `jvmd investigate <pid>`; open `report.md` first and read the verdict and findings. Do not restart before you have the report and, when relevant, a heap or thread dump.
2. Stabilise with the narrowest lever: roll back the last deploy, disable the feature flag, drain and restart one instance, or shed load. A restart is a valid mitigation after evidence is captured, never a substitute for it.
3. Escalate with evidence: a heap dump and MAT for memory, JFR or async-profiler for CPU, the thread dump and lock owner for concurrency. Hand off with the case directory, the risk score, the timeline, and the report's `COPY AI PROMPT` text, which already contains the verdict, VM facts, findings, evidence and top stacks.

## Prevent the next one

- Put this runbook in the alert annotation and keep `jvmd` on the image or in a known path; the tool needs a full JDK (`jcmd`), not a JRE.
- Store each `case-*` directory with the incident ticket. The report is self-contained HTML and opens without the original host.
- Rehearse with `jvmd demo deadlock` and the other scenarios during onboarding, so responders know what a healthy report looks like before they see a critical one.
- Dashboard the five leading indicators: CPU, heap after old GC, live thread count, pool waiters, and downstream p99. Most pages start as a trend one of these showed an hour earlier.
- Keep `-XX:+HeapDumpOnOutOfMemoryError` and a dump directory with free space on every JVM; the one incident where it is missing will be the one that matters.
- Timebox triage to fifteen minutes. If the verdict is still unclear, escalate with the report instead of continuing alone.
- Write the timeline into the incident ticket as you go: alert, first sample, mitigation, recovery. The report is evidence, not a timeline.
- Keep one known-good case per service and compare thread counts, heap and verdict after each release.

## Related

- [Deadlock](./deadlock.md)
- [CPU spin](./cpu-spin.md)
- [Pool starvation](./pool-starvation.md)
- [Memory pressure](./memory-pressure.md)
