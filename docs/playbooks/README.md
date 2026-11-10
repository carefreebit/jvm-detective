# JVM Detective playbooks

These are the runbooks for the first fifteen minutes of a JVM incident. Each one starts from a realistic pager message, shows how to confirm the diagnosis with `jcmd` and `jvmd`, then how to stop the bleeding and keep it from happening again. They assume Java 17 on Linux; Windows differences are noted inline.

Start with [triage](./triage.md) if you do not yet know which resource is saturated. Every command uses PID `4321` as a placeholder; replace it with the process you saw in `jvmd list`.

## Playbooks

| Incident | First signal | Playbook |
|---|---|---|
| Not sure where to start | Latency up across several endpoints, one obvious metric missing | [Triage](./triage.md) |
| Deadlock | `Found one Java-level deadlock` in `Thread.print -l`, or two names in a lock cycle | [Deadlock](./deadlock.md) |
| CPU spin | The same stack, frame for frame, in every sample; CPU pinned at the core count | [CPU spin](./cpu-spin.md) |
| Lock contention | Dozens of `BLOCKED` threads queued on one monitor id | [Lock contention](./lock-contention.md) |
| Thread leak | Live thread count climbing; one name family dominates the dump | [Thread leak](./thread-leak.md) |
| Pool starvation | Request threads in `HikariPool.getConnection`; connection timeout in the log | [Pool starvation](./pool-starvation.md) |
| Downstream stall | Threads in `Future.get` or `socketRead0`; local CPU low; downstream 504s | [Downstream stall](./downstream-stall.md) |
| Memory pressure | Heap above 90% after old GC, frequent Full GCs, or a container `OOMKilled` | [Memory pressure](./memory-pressure.md) |

## What the tool gives you

`jvmd investigate <pid>` samples six thread dumps by default and writes a case to `jvmd-reports/case-<id>-<timestamp>/`:

```
jvmd list
jvmd investigate 4321 --samples 6 --interval-ms 500
jvmd analyze /tmp/thread-dump.txt
jvmd demo deadlock
```

The case contains `report.html`, `report.md`, `report.json` and the first `thread-dump.txt`. The verdict and risk score summarise the whole analysis, so read them before you open the thread list:

- Any CRITICAL finding makes the case CRITICAL, a HIGH makes it DEGRADED, and MEDIUM or LOW makes it WATCH. The HTML risk score and SEV label are derived from the same findings.
- Each finding carries the literal evidence lines and a "do this first" fix.
- The HTML report filters threads by state and name and shows wait-for chains, so you can confirm a cycle instead of guessing.
- `COPY AI PROMPT` produces a handoff text with the verdict, VM facts, findings and top stacks; `COPY COMMAND` reproduces the run.
- Exit codes are 0 healthy, 1 degraded, 2 critical, which makes the tool usable in a script.

`jvmd analyze` works on a dump you already have, but a single snapshot cannot distinguish a hotspot from ordinary work; it marks CPU findings as candidates, and VM facts show as `pid 0` and `runtime unknown`. `jvmd demo <scenario>` runs the same pipeline against a bundled scenario (deadlock, cpu-spin, thread-leak, lock-contention, pool-starvation, downstream-block) if you want to see a report before you need one.

## Finding ids in the report

The report uses internal ids; the playbook names differ because they describe the incident, not the detector.

| Finding id | Playbook |
|---|---|
| `deadlock` | [Deadlock](./deadlock.md) |
| `cpu-hotspot` | [CPU spin](./cpu-spin.md) |
| `lock-contention` | [Lock contention](./lock-contention.md) |
| `thread-leak` | [Thread leak](./thread-leak.md) |
| `connection-starvation` | [Pool starvation](./pool-starvation.md) |
| `downstream-stall` | [Downstream stall](./downstream-stall.md) |
| `heap-pressure` | [Memory pressure](./memory-pressure.md) |

`baseline` is informational only and points back to [triage](./triage.md).

## Before you use these in anger

- Run the tool as the same OS user that owns the JVM, or `jcmd` will refuse to attach.
- The host needs a full JDK, not a JRE; there is nothing to install beyond the `jvmd` jar and `jcmd`.
- Capture evidence before a restart: a thread dump or heap dump is the only way to explain the incident afterwards.
- These playbooks cover diagnosis and mitigation, not capacity planning. Sizing still needs production numbers.
