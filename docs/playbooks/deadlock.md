# Deadlock

**Typical pager message:** "checkout-api: 12 requests stuck for 8 minutes, thread pool exhausted, CPU 3%, nothing in the error log"

## What is actually happening

A deadlock is a cycle of threads where each one holds a lock the next one needs. With `synchronized`, a waiter parks in BLOCKED state with no timeout and no cancellation, so the cycle can never resolve itself. The threads are healthy; they are waiting for a monitor that will never be released. The pool drains one thread at a time until the service accepts no work, while CPU, GC and error rates stay flat. That is why the page rarely says "deadlock".

## Confirm it in 60 seconds

```
jcmd 4321 Thread.print -l | grep -n -A 25 "Found one Java-level deadlock"
jcmd 4321 Thread.print -l > /tmp/deadlock-$(date +%s).txt
jvmd investigate 4321 --samples 6 --interval-ms 500
```

On Windows use `jcmd.exe` and a literal path such as `%TEMP%\deadlock.txt`; the JVM-side flags are identical.

When the diagnosis is right the dump contains this:

> Found one Java-level deadlock:
> =============================
> "order-worker-1":
>   waiting to lock monitor 0x00007f6c4800a2c8 (object 0x00000006214e03e0, a java.lang.Object),
>   which is held by "order-worker-2"
> "order-worker-2":
>   waiting to lock monitor 0x00007f6c4800a3d8 (object 0x00000006214e03d0, a java.lang.Object),
>   which is held by "order-worker-1"

jvmd condenses the same cycle to `2 thread(s) in a lock cycle: order-worker-1, order-worker-2` and one `waits for <id> ... held by ...` line per thread.

## Read the evidence

- `BLOCKED` plus `waiting to lock <0x...>` on both sides is the signature. BLOCKED means waiting to enter a `synchronized` block; it will not time out on its own.
- `WAITING` is `Object.wait()` or `LockSupport.park` and is normal for idle pool threads. It only matters here when the wait-for chain leads back to a thread that is itself waiting.
- `TIMED_WAITING` in `Thread.sleep` or `parkNanos` will recover by itself. It can still be a latency bug, but it is not a deadlock.
- `RUNNABLE` members of the cycle are usually the owners doing work, not victims. Do not restart the JVM just because they look busy.
- With `-l`, `Locked ownable synchronizers` exposes `ReentrantLock` and other AQS-based locks ownership. HotSpot's detector reports monitor cycles; jvmd also walks wait-for edges, so it can show a `ReentrantLock` cycle the JVM did not label.
- A cycle is required. Thirty threads queued behind one owner with no edge back is lock contention, not deadlock; the fix is different.
- The `Found one Java-level deadlock` section prints both the monitor address and the object id; the per-thread section prints just the object id as `waiting to lock <0x...>`. Pair owner and waiter by thread name, not by the hex values alone.
- HotSpot's `Found one Java-level deadlock` section only knows monitor cycles. A `ReentrantLock` cycle can exist without that section, which is why jvmd also walks the wait-for edges.

## Fix it now

1. Capture the dump before touching the process: `jcmd 4321 Thread.print -l > /tmp/deadlock.txt`. A restart erases the only direct evidence.
2. Recover users by restarting the instance or rolling the pod (`kubectl rollout restart deploy/checkout-api`). A monitor deadlock cannot be broken from outside; the threads will never wake. Drain the node first if the platform supports it. Do not lead with `kill -9`; it skips shutdown hooks and in-flight writes and still leaves you no evidence. Do not call `Thread.stop()` either: it releases monitors at an arbitrary point and can leave shared state half-updated (deprecated, and it throws `UnsupportedOperationException` on JDK 20+).
3. Make the cycle impossible in code: define one global lock order and enforce it in every adapter, or collapse the two locks into one coarse lock. Where blocking is unavoidable, use `ReentrantLock.tryLock(200, TimeUnit.MILLISECONDS)` and fail the request with a clear error instead of hanging forever.

## Prevent the next one

- Write down the lock order next to the classes that take the locks, and add a unit test with two threads and a `@Timeout` that reproduces the interleaving; it fails in seconds instead of in production.
- Run a scheduled `ThreadMXBean.findDeadlockedThreads()` check every 30 seconds and log plus alert on a non-empty result. This costs almost nothing and names the threads immediately.
- Alert on `blocked threads > 20% of the request pool for 2 minutes`, not only on error rate. A deadlocked service returns no errors, just timeouts.
- Treat "lock inside lock" as a review blocker unless the ordering is documented. A single coarse lock is often cheaper than a clever hierarchy.
- Never call unknown code while holding a lock: listeners, callbacks or a lazy proxy's `toString` can take another lock and escape your ordering.
- Cap the wait for a lock and log the timeout with both thread names. A timeout is a cheap alarm; a hang is an outage.

## Related

- [Lock contention](./lock-contention.md)
- [Triage](./triage.md)
