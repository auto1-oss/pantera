# WS2 — HA Correctness

- **Status:** 📝 DRAFT
- **Depends on:** none
- **Blocks:** any "run N nodes" / "production HA" claim
- **Decision-gated:** no
- **Size:** L. Split into WS2.1–WS2.7; the trio (2.1–2.3) is mandatory before HA can be claimed truthful.

## 1. Problem & goal

Multi-node Pantera behind a load balancer (shared PostgreSQL + S3 + Valkey) is **unsafe today**: with the intended topology (Valkey on) you get **both data loss and security holes**. Goal: make N-node operation correct — no lost audit/index data, no re-honored revoked tokens, no unbounded stale authorization — and make rolling deploys non-disruptive.

Explicitly **not** goals for 2.3.0: leader election (unnecessary once 2.2 lands — pg_cron + `FOR UPDATE SKIP LOCKED` cover singleton work), Valkey **Cluster** (use Sentinel failover — the code uses global keys + one pub/sub channel, so sharding adds nothing), a `pantera_nodes` heartbeat registry (visibility, not correctness).

## 2. Current state (evidence)

**The MUST-FIX trio:**
1. **Token revocation doesn't survive node restart on the Valkey path.** `ValkeyRevocationBlocklist` writes revocations to Valkey (`setex`+publish, `:146-165`) but `isRevokedJti/User` read **only the local `ConcurrentHashMap`** (`:120-143`) and there is **no boot hydration** (ctor `:106-117`). It is the impl the HA topology selects (`VertxMain.java:311-317`); the correct DB-poll `DbRevocationBlocklist` runs only single-node (`:325-328`). ⇒ a node that boots/restarts after a revocation re-honors already-revoked, unexpired tokens for their full TTL. **Security.**
2. **Clustered-Quartz event pipeline self-destructs.** `QuartzService` calls `scheduler.clear()` unconditionally on JDBC boot (`:121`) — wipes all nodes' jobs from the shared `QRTZ_*` tables. Worse: the event-drain job carries a node-local `Queue`/`Consumer` via a **static per-JVM** `JobDataRegistry` (`:59`) with only a key in the persisted `JobDataMap`; clustered Quartz does **not** pin repeating triggers to the scheduling node, so node B fires node A's `EventsProcessor`, `lookup` returns null → `stopJob → deleteJob` on the shared tables (`EventsProcessor.java:67-68`, `QuartzJob.java:36-47`). Node A's `DbConsumer` input deque never drains. **Lost: `artifact_publish` audit records + search-index rows + publish-dates** (blobs survive in S3). The `clear()` comment ("Old jobs would fire with null dependencies, fail, and loop", `:117-120`) is evidence this was already observed.
3. **Authorization + admin-settings don't propagate cross-node.** Policy (roles/permissions) cache has a pub/sub *receiver* (`YamlSettings.java:340`) but **no publisher** — the policy is passed raw (`PanteraCaches.java:109-129`), never wrapped `PublishingCleanable` like `auth`/`filters`; `RoleHandler`/`UserHandler` call `invalidate` locally only. It's `expireAfterAccess(3min)` (`CachedDbPolicy.java:89`) so a continuously-hit role/permission **never expires on peers** → unbounded stale authz. Same shape for the breaker/bulkhead settings loaders (`UpstreamBreakerSettingsLoader.java:84,108` `AtomicReference`, no TTL, local-only `invalidate()` from `AdminAuthHandler.java:216,338`) → peers stale until restart. **Security / correctness.**

**Hardening:**
4. **No real readiness probe.** Main-port health returns 200 with zero I/O (`HealthSlice.java:31-35`); metrics-port `/ready` checks only `registry != null` (`AsyncMetricsVerticle.java:400-415`). A node with a dead Hikari pool or unreachable S3 reports healthy and keeps taking LB traffic → 5xx.
5. **Graceful drain not readiness-gated.** HTTP drains 30 s (`VertxSliceServer.java:84,451-490`) but SIGTERM flips to 503 immediately (`:550-552`) with no pre-stop readiness flip + LB-notice delay → deploy-time 503s for in-flight LB-routed requests.
6. **`DbConsumer` buffer loss on shutdown.** No `close()`/flush; `VertxMain.stop()` never flushes; the `artifact_publish` audit record is emitted from inside the ~2 s batch (`DbConsumer.java:371`) → even a *graceful* shutdown drops the last window's audit records (index self-heals; audit is permanently lost — a compliance hole).
7. **Download-token weaknesses.** Per-node default secret (`ArtifactHandler.java:73-80`) → cross-node tokens 401 unless `PANTERA_DOWNLOAD_TOKEN_SECRET` pinned; advertised "single-use" with no consumed-token store (`:841-887`) → replayable for the 60 s TTL; non-constant-time `.equals()` compare (`:864`); peer revocation TTL uses a fixed 2 h regardless of real token life.

## 3. Target design

### WS2.1 — Correct token revocation in the HA path (security)
Preferred: make revocation **DB-durable and Valkey-accelerated**. `revoke` writes the DB row (already the fallback's source of truth) **and** publishes over Valkey for instant fan-out; `isRevoked*` checks local map → on miss, a bounded DB/Valkey read; **hydrate the full active-revocation set on boot** (`pollSince(EPOCH)` as `DbRevocationBlocklist` already does, `:123-146`). Carry the token's **real remaining TTL** in the pub/sub payload (fixes the fixed-2 h bug). Net: no node ever honors a revoked-but-unexpired token, regardless of boot order or a missed pub/sub message.

### WS2.2 — De-cluster the artifact-event pipeline (correctness/data-loss)
- Remove `QuartzService.scheduler.clear()` (`:121`) and the `stopJob → deleteJob` self-destruct.
- Stop running node-local-data jobs (`EventsProcessor`) on **clustered** Quartz. Drain each node's `DbConsumer` input on a **per-node `ScheduledExecutor`** (mirror `RuntimeSettingsCache`'s own timer), or move the queue to a DB table drained with `FOR UPDATE SKIP LOCKED`. Node-local in-memory work must never be scheduled through a cluster-shared store.
- Keep genuinely-shared cron (cleanup scripts) on clustered Quartz — those are idempotent/DB-guarded and correct.

### WS2.3 — Propagate authz + admin-settings cross-node (security/correctness)
- Wrap the policy cache in `PublishingCleanable("policy", …)` exactly like `auth`/`filters` (receiver already registered); publish on every `RoleHandler`/`UserHandler` mutation. Consider switching `expireAfterAccess` → `expireAfterWrite` so a bounded backstop exists even if a message is missed.
- Broadcast `loader.invalidate()` for the breaker/bulkhead/upstream-breaker settings loaders over a pub/sub channel (or move those keys behind `RuntimeSettingsCache`, which is already cross-node via Postgres LISTEN/NOTIFY).

### WS2.4 — Real readiness probe + pre-stop gate (availability)
- `/readyz` on the **traffic port** that checks DB (`SELECT 1`), S3 (HEAD bucket), and Valkey (PING, tolerant/degraded-aware); distinct from a cheap liveness `/livez`.
- Pre-stop hook: on SIGTERM, flip readiness to 503 and **wait ~2× the LB health-check interval** before the socket starts rejecting, so the LB deregisters the node before in-flight routing stops.

### WS2.5 — Graceful `DbConsumer` flush (durability)
- `DbConsumer.close()` completes the `PublishSubject`, drains the deque, and blocks (bounded) until the final batch is persisted; call it from `VertxMain.stop()` before the DataSource shuts down. No audit record lost on a clean shutdown.

### WS2.6 — Download-token hardening (security/availability)
- Require/derive a shared secret; **fail closed** in multi-node if unset. Use `MessageDigest.isEqual` (constant-time). Make "single-use" real via a short-TTL Valkey nonce store (or drop the single-use claim). Use the token's real TTL for peer revocation.

### WS2.7 — Cache-coherency completeness sweep
Audit every in-memory cache and ensure each has cross-node invalidation or a bounded TTL backstop. Known-NO today: **policy** (WS2.3), **breaker/bulkhead settings loaders** (WS2.3), `StorageMetaCache` (low-risk, TTL self-heals — add a note or a broadcast). Known-YES (leave): negative cache, users/auth, filters, user-enabled, artifact-index, cooldown decision + filtered-metadata, runtime settings. Document the final matrix in `docs/ha-deployment/`.

### Post-2.3.0 (spec-noted, not built here)
Valkey Sentinel failover; `pantera_nodes` heartbeat registry for fleet visibility. Leader election explicitly **not** built.

## 4. Implementation plan (ordered)

1. **WS2.1** revocation (security first) — `ValkeyRevocationBlocklist` + `VertxMain` selection + boot hydration + TTL payload.
2. **WS2.2** event-pipeline de-cluster — `QuartzService`, `EventsProcessor`, `JobDataRegistry`, `DbConsumer` scheduling.
3. **WS2.3** authz + settings propagation — `PanteraCaches`/`YamlSettings` policy wrap, settings-loader broadcast.
4. **WS2.5** `DbConsumer.close()` + `VertxMain.stop()` (pairs naturally with 2.2).
5. **WS2.4** readiness probe + pre-stop gate.
6. **WS2.6** download-token hardening.
7. **WS2.7** coherency sweep + docs.

## 5. Acceptance criteria

1. **Revocation survives restart** (two-instance + restart test): revoke a token on node A; a node that boots afterward rejects it; a peer that missed the pub/sub message rejects it after its next DB read; the rejection window respects the token's real TTL. (Invocation/state assertions, not wall-clock.)
2. **Event pipeline correct under clustered Quartz** (two-scheduler test against a shared JDBC store): node B firing a trigger does **not** delete node A's job; every `artifact_publish` event enqueued on a node is persisted (audit + index) even when another node holds the trigger; a rolling restart loses **zero** enqueued events.
3. **Authz propagates** (two-instance test): a role/permission change on node A is reflected on node B within the pub/sub round-trip; a continuously-hit permission still converges (no `expireAfterAccess` starvation).
4. **Settings propagate** (two-instance test): a breaker/bulkhead threshold change via the admin API on node A takes effect on node B without a restart.
5. **Readiness reflects dependencies**: with the DB pool killed, `/readyz` returns non-200; `/livez` stays 200.
6. **Graceful drain**: SIGTERM → readiness flips to 503, in-flight requests complete, `DbConsumer` flushes its last batch (audit record count matches events served), socket rejects only after the drain window.
7. **Download tokens**: minted on node A validate on node B with a pinned secret; replay of a consumed single-use token is rejected; comparison is constant-time; unset secret in multi-node fails closed.

## 6. Test requirements

- Prefer two-instance integration tests sharing an in-memory/containerized Postgres + a Valkey fake/container; assert via state + invocation counts + latches, never wall-clock (CLAUDE.md doctrine). Use `@Timeout` to turn a blocking regression into a deterministic failure.
- The event-pipeline test must run **two schedulers against one JDBC job store** to reproduce the cross-node trigger acquisition — this is the crux and today has no coverage.
- Shutdown/drain test uses a real `VertxSliceServer` with a latch-gated slow handler.

## 7. Out of scope

- Leader election; Valkey Cluster; node registry/heartbeats (spec-noted for later).
- The storage-layer cross-node coherence (that's WS1.4/WS1.5).

## 8. Risks & rollback

- WS2.2 touches the audit/index correctness core — highest-risk item here; land it behind the two-scheduler test before anything else in WS2 flips defaults.
- Removing `scheduler.clear()` means a pre-existing bad job in a shared store won't be auto-wiped — provide a one-time admin/boot migration to purge stale `QRTZ_*` rows instead of the blanket clear.
- Revocation change is security-sensitive: keep the DB path authoritative so a Valkey outage degrades to correct-but-slower, never to fail-open.

## 9. Docs & observability

- `docs/ha-deployment/` — corrected HA topology, the final cache-coherency matrix, shared-secret pinning, Sentinel recommendation, readiness/drain behavior, the "eventually-consistent across restart" note where it still applies.
- `docs/admin-guide/environment-variables.md` — `PANTERA_DOWNLOAD_TOKEN_SECRET` now required in multi-node; any new readiness/drain tunables.
- Metrics (WS7): revocation-set size, event-queue depth + drain lag, readiness state, settings-reload propagation. Runbooks for each new alert.
- CHANGELOG under `### 🔒 Security` (revocation, authz propagation, download token) + `### 🔧 Bug fixes` (event pipeline).
