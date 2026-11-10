# Downstream stall

**Typical pager message:** "gateway-api: p99 9.8s and 40% 5xx, request threads parked in `Future.get`, payments downstream returning 504, local CPU 20%"

## What is actually happening

Request threads are blocked on a synchronous call to another service, and that call has no effective deadline. When the dependency slows down, threads accumulate in the calling pool instead of failing, so a slow dependency becomes a full outage of an otherwise healthy service. Retries then multiply the load on the struggling dependency, which slows it further. Adding threads or pool size increases the number of concurrent calls to a system that is already saturated, so the obvious mitigation makes the incident worse.

## Confirm it in 60 seconds

```
jcmd 4321 Thread.print -l | grep -B 3 -A 15 "java.util.concurrent.Future.get"
jcmd 4321 Thread.print -l | grep -B 2 -A 12 -E "socketRead0|HttpClient.send|okhttp3|lettuce|jedis"
jvmd investigate 4321 --samples 6 --interval-ms 500
```

On Windows use `jcmd.exe` and `Select-String` with the same patterns; the JVM frames are identical.

The report shows one call path repeated across the pool:

> 10 threads share the same stack
> top frame: java.net.SocketInputStream.socketRead0(Native Method)
> frames: com.acme.pay.PaymentClient.authorize -> okhttp3.internal.http2.Http2Stream -> ...

## Read the evidence

- `TIMED_WAITING` or `WAITING` inside `socketRead0` is a network read that has not returned. `RUNNABLE` inside a native socket read is also blocked; the thread state alone does not tell you it is doing work.
- `Future.get()` with no argument is an unbounded wait. `Future.get(2, TimeUnit.SECONDS)` shows `parkNanos` and is bounded, but still hurts if every thread reaches it at once.
- jvmd groups threads only when the same six-frame fingerprint appears at least eight times, and only when the fingerprint contains a client frame (`future.get`, `httpclient.send`, `okhttp3`, `lettuce`, `jedis`). That repetition is what separates a stalled dependency from ordinary traffic.
- Compare the blocked frames with local resource failures: `HikariPool.getConnection` means the local pool is exhausted ([pool starvation](./pool-starvation.md)); `BLOCKED` on a monitor means a lock ([lock contention](./lock-contention.md)); no shared frame at all means the load is simply too high.
- A retry storm looks like a stable thread count with downstream QPS several times normal, plus `Retry` or `retrying` log lines. The downstream sees the spike even if your threads look calm.
- Cascading failure is visible as unrelated endpoints failing at the same time: they share the request pool, and the stalled calls have taken all of it.
- `WAITING` on `CompletableFuture$Signaller` or a `ForkJoinPool` frame is the same failure in a different costume. Confirm the client frame before blaming the HTTP library.
- The dump does not print the target URL. Correlate the host from config, or use `ss -tnp` to map the socket to a remote address before deciding which dependency is slow.
- `Future.get()` inside a `@Transactional` method also holds a database connection while it waits, which turns a downstream stall into pool starvation.
- A shared `ForkJoinPool.commonPool()` lets one slow task starve unrelated parallel streams. Use a dedicated executor with a bounded queue for dependency calls.

## Fix it now

1. Stop the amplification first: disable automatic retries or reduce them to one attempt with jitter, open the circuit breaker, and shed load at the edge. This is usually a config or feature-flag change and is faster than a deploy.
2. Put a deadline on every call in the path: HTTP connect timeout 200–500 ms, read timeout 1–2 s, `Future.get(1500, MILLISECONDS)`, Redis command timeout, JDBC `queryTimeout` and `socketTimeout`. The sum of hop timeouts must fit inside the caller's timeout, which must fit inside the gateway's.
3. Isolate the dependency: a Resilience4j `Bulkhead` (semaphore or thread pool) per dependency, a `CircuitBreaker` with a failure-rate threshold, and a `TimeLimiter`. Return a fallback (cached value, queued retry, clear 503) instead of holding the request thread.

## Prevent the next one

- Alert on downstream latency and error rate per dependency, not just on your own 5xx; a dependency at p99 5 s is your incident before it becomes one.
- Make timeouts a code-review requirement and enforce it with an ArchUnit or a startup check that every client bean has connect, read and total timeouts.
- Test with injected latency (Toxiproxy or a stub at 2 s) and assert the service still meets its SLO and fails fast for the affected endpoint.
- Give retries a budget: no more than 10% of calls, exponential backoff with jitter, and only for idempotent operations.
- Keep request pools bounded with an abort or caller-runs policy, so overload surfaces as fast 503s rather than a growing queue.
- Keep the gateway and load-balancer timeouts slightly above the application deadline, never below; otherwise callers see 504s while the application is still working and holding threads.
- Give each dependency its own connection or concurrency pool with a per-host cap, so one slow host cannot consume every connection.
- Put the dependency's p99 on the same dashboard as your own latency. The next responder should see the causal link without opening a thread dump.
- Pass one total deadline through the call chain instead of tuning each socket. Retries and nested calls then share a single budget, and the request fails before the caller gives up.

## Related

- [Pool starvation](./pool-starvation.md)
- [Lock contention](./lock-contention.md)
- [Triage](./triage.md)
