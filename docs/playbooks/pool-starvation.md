# Pool starvation

**Typical pager message:** "orders-api: checkout p99 30s, logs full of `HikariPool-1 - Connection is not available, request timed out after 30000ms`, 12 threads waiting on the pool"

## What is actually happening

A JDBC pool holds a fixed number of physical connections. Each unit of work borrows one for the length of its transaction, and when concurrency exceeds the pool size the extra request threads park inside `getConnection` until `connectionTimeout` expires. Threads are cheap; connections are the scarce resource, and every waiter is a request that already occupies a request-pool thread. Nested work that borrows a second connection while the first is still held multiplies the demand and can exhaust a pool with a single caller per thread.

## Confirm it in 60 seconds

```
jcmd 4321 Thread.print -l | grep -c "com.zaxxer.hikari.pool.HikariPool.getConnection"
jcmd 4321 Thread.print -l | grep -B 3 -A 12 "getConnection"
jvmd investigate 4321 --samples 6 --interval-ms 500
```

On Windows the same `jcmd.exe` commands work; use `Select-String "getConnection" -Context 3,12` instead of `grep`.

The report points at the borrow site, and the application log states it plainly:

> 12 threads are parked in a connection-pool call
> 12 threads share the same stack
> top frame: com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:212)

> HikariPool-1 - Connection is not available, request timed out after 30000ms.

## Read the evidence

- Waiters are `TIMED_WAITING` or `WAITING` in `getConnection`, not `RUNNABLE`. They are not talking to the database; they are queuing for the right to talk to it.
- The real DB work looks different: `socketRead0`, `MysqlIO.readPacket` or `PGStream.receive` under a driver frame. If every active connection is sitting in a slow query, the database is slow, not the pool small — adding connections can make it worse.
- Count waiters against `maximumPoolSize`. Five waiters is a warning; twelve with a thirty-second timeout means a thirty-second queue and a request pool that will exhaust next.
- Nested borrows are the classic multiplier: an outer `@Transactional` method borrows one connection, then a `REQUIRES_NEW` call or a second `DataSource` borrows another from the same pool. Two callers can then deadlock the pool. A stack that shows `getConnection` under an existing JDBC or transaction frame is a strong signal.
- Leaks look different: active connections never return, no thread is inside a JDBC call, and the pool reports zero idle. `leakDetectionThreshold` names the borrower.
- A pool that is too small shows idle connections at zero and waiters climbing at constant load. A pool that is too large hides the symptom and shifts the failure to the database's `max_connections`.
- HikariCP defaults to a maximum of 10 connections and a 30-second `connectionTimeout`. Druid's equivalents are `maxActive` and `maxWait`; `removeAbandoned` is a last resort that hides the bug, not a fix.
- Check whether the pool is shared. Two `DataSource` beans with separate pools can each look healthy while one endpoint starves on its own pool.
- A connection held across an HTTP or Redis call turns the pool size into the concurrency limit for a remote system, and a slow peer will exhaust it.

## Fix it now

1. Decide which failure you have. In one shell, sample the pool counters (JMX or the Micrometer `hikaricp.connections.active/idle/pending` gauges), and on the database run `SELECT pid, state, wait_event, query_start, query FROM pg_stat_activity` (PostgreSQL) or `SHOW PROCESSLIST` (MySQL). Slow queries mean fix the query; idle-but-unreturned means a leak; all-busy-short-queries means size or concurrency.
2. Reduce the queue: lower `connectionTimeout` to 2–5 seconds so callers fail fast instead of stacking, and keep it below the request and gateway timeouts. Shed load or scale pods only if the database has headroom; the product of pods and `maximumPoolSize` must stay under the server's connection limit.
3. Fix the transaction shape: one connection per unit of work, no HTTP, Redis or file I/O inside a transaction, and no nested borrow while an outer transaction is open. Size with Little's law — connections needed = arrival rate x average hold time — plus 20–30% headroom, measured, not guessed.

## Prevent the next one

- Enable `leakDetectionThreshold` (for example 20000 ms, above the slowest legitimate transaction) and treat every leak warning as a bug with the borrower's stack attached.
- Alert on pool waiters and acquisition latency (`threadsAwaitingConnection > 0` for a minute, or p99 acquire > 50 ms), not only on the timeout log line.
- Keep transactions short and set a server-side statement timeout so one bad query cannot hold a connection for minutes.
- Load-test at 2x peak and assert pool saturation and p99 acquire time; add a check that pods x pool size fits the database limit.
- Isolate slow reporting or batch work on its own small pool so it cannot consume the checkout pool.
- Log pool size, `connectionTimeout` and the database host at startup, and diff them on every deploy; a config change that halves the pool should never be silent.
- Test the failure mode in staging: saturate the pool and confirm the caller gets a clean 503 within the timeout, not a 30-second hang.
- Do not retry a connection timeout at the HTTP layer without a budget. Each retry re-enters the same exhausted pool and makes the queue longer.

## Related

- [Downstream stall](./downstream-stall.md)
- [Thread leak](./thread-leak.md)
- [Triage](./triage.md)
