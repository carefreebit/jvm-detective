# Lock contention

**Typical pager message:** "order-api: 37 of 40 request threads BLOCKED, throughput fell from 120 to 9 rps, CPU 15%, no errors"

## What is actually happening

A monitor is exclusive: while one thread holds it, every other thread that reaches the same `synchronized` block parks in BLOCKED state. If the owner holds the lock for 900 ms and forty request threads need it, the fortieth waits roughly 36 seconds. CPU stays low because BLOCKED threads are not scheduled, so dashboards look calm while latency collapses. The distinction that matters is one hot monitor serialising the whole pool versus many short-lived locks that are merely busy.

## Confirm it in 60 seconds

```
jcmd 4321 Thread.print -l | grep -c "BLOCKED (on object monitor)"
jcmd 4321 Thread.print -l | grep -B 2 -A 8 "waiting to lock"
jvmd investigate 4321 --samples 6 --interval-ms 500
```

On Windows run the same `jcmd.exe` commands from PowerShell; `grep` is available in Git Bash, or use `Select-String "waiting to lock" -Context 2,8`.

The report reduces the dump to one owner and its queue:

> 37 threads BLOCKED on <0x00000006214e0788> (java.lang.Object)
> owner: "http-nio-8080-exec-owner" at com.acme.order.OrderCache.refresh(OrderCache.java:88)
> waiting: http-nio-8080-exec-100, http-nio-8080-exec-101, http-nio-8080-exec-102, ...

## Read the evidence

- `BLOCKED` with `waiting to lock <0x...>` means the thread reached a `synchronized` block and lost the race. Exactly one thread owns that id; everyone else waits without a timeout.
- `WAITING` is different: the owner called `Object.wait()` and released the monitor, or a thread parked. Those threads are not competing for entry, so do not count them as lock waiters.
- `TIMED_WAITING` is `Thread.sleep`, `parkNanos` or a timed wait. The owner is often here — sleeping or doing I/O inside the critical section is the classic cause.
- `RUNNABLE` owner means it is computing inside the section. That is still contention, but the fix is to shorten the computation rather than to move I/O out.
- The owner's top frame is the finding. `Thread.sleep`, JDBC, HTTP, Redis or logging under the lock explains the hold time; `Object.wait` means the lock is being used as a condition and the design needs a queue.
- jvmd reports the hottest monitor plus `other contended locks: N`. Many locks with 3–5 waiters each is diffuse contention; one lock with dozens is serialisation. The fixes differ.
- Three waiters is a signal; dozens is an outage. Multiply waiters by hold time to estimate the latency you are adding.
- `-l` prints `Locked ownable synchronizers` for `ReentrantLock` and other AQS-based locks; a plain `synchronized` monitor never appears there, so an empty list does not mean the thread holds nothing.
- Biased locking has been disabled by default since JDK 15, so even an uncontended `synchronized` costs an atomic. Do not size a hot path from JDK 8 advice.
- The waiting list is not strictly ordered by arrival. Compare two dumps to see who is making progress and who has been parked the whole time.

## Fix it now

1. Identify what the owner is doing: take three dumps five seconds apart (`for i in 1 2 3; do jcmd 4321 Thread.print -l > /tmp/d$i.txt; sleep 5; done`) and compare the owner's stack. If it never moves, it is stuck in I/O or a deadlock, not merely slow.
2. Mitigate without making it worse: disable the endpoint, shed load, or roll back the deploy that widened the critical section. Restart only if the owner is permanently stuck. Increasing the pool buys nothing — throughput is capped by the lock, and a larger pool just creates a longer queue and more memory.
3. Fix the structure: move I/O, logging and RPC outside the lock; compute on a snapshot and publish it with one short assignment. Replace the global lock with per-key locks (`ConcurrentHashMap.computeIfAbsent` or a striped lock) when keys are independent, and use `ReentrantReadWriteLock` only when reads dominate and the read path is genuinely read-only.

## Prevent the next one

- Record lock wait time with JFR `jdk.JavaMonitorEnter` at a 10 ms threshold, and alert when the p99 exceeds the request SLO budget.
- Add a rule to review: no network, disk, logging or `Thread.sleep` inside a `synchronized` block or a `ReentrantLock` section.
- Choose `synchronized` for simple, short, uncontended guards. Choose `ReentrantLock` when you need `tryLock(timeout)`, interruptibility, fairness or multiple conditions; note that `fair=true` reduces starvation but lowers throughput, and `tryLock` without a timeout still hangs.
- Run a contention test at target concurrency (JMH or a k6 scenario) so a 900 ms hold time is caught before production.
- Prefer immutable state and message passing. The cheapest lock is the one you do not need.
- Log the owner's stack when a waiter passes a threshold (50 ms is a reasonable start), so the next page already contains the culprit.
- Measure hold time, not just waiter count: one lock held for two seconds is worse than twenty held for a millisecond.
- If you need fairness, prefer a queue such as `ArrayBlockingQueue` over a `fair=true` lock; fair locks trade throughput for ordering and are rarely what an API needs.
- Keep the state a lock protects small and self-contained. A critical section that touches three services is a design smell, not a tuning problem.

## Related

- [Deadlock](./deadlock.md)
- [Pool starvation](./pool-starvation.md)
- [Triage](./triage.md)
