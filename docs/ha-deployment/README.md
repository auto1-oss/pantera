# HA Deployment Artifacts

This directory holds sample deployment configuration for a multi-node
Pantera cluster (`pantera-ha.yml`, `docker-compose-ha.yml`,
`nginx-ha.conf`). For the narrative HA guide — architecture, shared
services, load balancer setup, cache invalidation, token revocation, and
known gaps — see
[High Availability](../admin-guide/high-availability.md) in the admin
guide.

## Cache / state coherency matrix (as of 2.3.0)

Every in-memory cache and cross-node state mechanism, and whether it has
cross-node invalidation or a bounded TTL backstop:

| State | Cross-node mechanism | Bounded backstop | Status |
|---|---|---|---|
| Token revocation | Valkey pub/sub (real remaining TTL in payload) | DB reconciliation poll, 5 s | Fixed 2.3.0 (WS2.1) |
| Artifact-events queue (audit + search index) | N/A — per-node scheduler, never cluster-shared | N/A (no cross-node race possible) | Fixed 2.3.0 (WS2.2) |
| Policy (roles/permissions) cache | Valkey pub/sub (`policy` cache type) | `expireAfterWrite`, 3 min | Fixed 2.3.0 (WS2.3) |
| Group-member circuit-breaker settings | Valkey pub/sub (`circuit-breaker-settings`) | None (loader caches until invalidated) | Fixed 2.3.0 (WS2.3) |
| Upstream HTTP circuit-breaker settings | Valkey pub/sub (`upstream-breaker-settings`) | None (loader caches until invalidated) | Fixed 2.3.0 (WS2.3) |
| Negative cache | Valkey pub/sub + Valkey L2 | TTL | Already correct (pre-2.3.0) |
| Users/auth cache | Valkey pub/sub (`auth` cache type) | TTL | Already correct (pre-2.3.0) |
| Filters cache | Valkey pub/sub (`filters` cache type) | TTL | Already correct (pre-2.3.0) |
| User-enabled cache | Valkey pub/sub | TTL | Already correct (pre-2.3.0) |
| Artifact index cache | Valkey pub/sub + post-commit invalidation | TTL | Already correct (pre-2.3.0) |
| Cooldown decision / filtered-metadata cache | Valkey pub/sub | TTL | Already correct (pre-2.3.0) |
| Runtime settings (`RuntimeSettingsCache`) | Postgres LISTEN/NOTIFY | 30 s polling fallback | Already correct (pre-2.3.0) |
| `StorageMetaCache` | None | TTL only | Known-low-risk gap, not yet addressed (WS2.7) |

Readiness probing, graceful drain, `DbConsumer` shutdown flush, and
download-token hardening (secret pinning, single-use enforcement,
constant-time comparison) are tracked separately and **not** covered by
this matrix — see the "Known Gaps" section of the
[High Availability](../admin-guide/high-availability.md#known-gaps) guide.
