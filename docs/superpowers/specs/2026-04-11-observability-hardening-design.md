# Observability Hardening — Design Spec

**Date**: 2026-04-11
**Status**: Draft — awaiting review
**Scope**: 5 items, all implemented together, no phases

---

## 1. Proxy Protocol v2 (AWS NLB)

### Problem
Behind an AWS NLB with Proxy Protocol v2 enabled, Pantera sees the NLB's IP as the client address. `X-Forwarded-For` is not set by NLB in TCP/TLS mode — the real IP is only available via the PROXY protocol header prepended to the TCP connection.

### Design

**Vert.x layer** (`VertxSliceServer`):
- Add `httpServerOptions.setUseProxyProtocol(true)` when config flag is set.
- Vert.x delegates to Netty's `HAProxyMessageDecoder` which parses both v1 (text) and v2 (binary) PROXY protocol headers.
- After decoding, `request.remoteAddress()` returns the real client IP.
- Requires `netty-codec-haproxy` on the classpath — already present transitively via `io.vertx:vertx-core`.

**Configuration** (`pantera.yml`):
```yaml
meta:
  proxy-protocol: true   # default: false
```

Read in `VertxMain` / `VertxSliceServer` as a boolean. When false (default), Vert.x does not expect the PROXY header — existing deployments without NLB are unaffected.

**Nginx** (`docker-compose/nginx/conf.d/default.conf`):
- For deployments where nginx sits between NLB and Pantera, nginx must also accept PROXY protocol:
  ```nginx
  listen 80 proxy_protocol;
  set_real_ip_from 10.0.0.0/8;  # NLB internal range
  real_ip_header proxy_protocol;
  ```
- `X-Real-IP` set to `$proxy_protocol_addr` instead of `$remote_addr`.
- Document both configurations (with nginx, without nginx / direct NLB → Vert.x).

**No change to `EcsLogEvent.extractClientIp`**: the existing fallback chain (X-Forwarded-For → X-Real-IP → remoteAddress) already works. With Proxy Protocol enabled, `remoteAddress` is the real IP, so the fallback produces the correct value even without X-Forwarded-For.

### Files to change
- `vertx-server/.../VertxSliceServer.java` — read config, set `useProxyProtocol`
- `pantera-main/.../VertxMain.java` — pass config to server
- `pantera-main/.../Settings.java` / `YamlSettings.java` — read `meta.proxy-protocol`
- `docker-compose/nginx/conf.d/default.conf` — add `proxy_protocol` listener variant (documented, not default)

---

## 2. Artifact Audit Logging

### Problem
Security team requires structured logs for every artifact upload, download, delete, and metadata resolution at INFO level so Elastic picks them up. Current state: uploads queue events but don't emit ECS logs; downloads have no event logging; deletes miss the actor.

### Design

**Dedicated logger**: `artifact.audit` (category in log4j2.xml, INFO level). Separate from `http.access` so Elastic can index it as a distinct data stream with its own retention policy.

**Four event actions**:

| Action | Trigger | Where |
|---|---|---|
| `artifact_upload` | Successful PUT/POST of an artifact | `SliceUpload`, `WheelSlice`, adapter upload handlers |
| `artifact_download` | Successful GET of an artifact binary | `SliceDownload`, proxy cache-hit and remote-fetch paths |
| `artifact_delete` | Successful DELETE of an artifact | `SliceDelete`, adapter delete handlers |
| `artifact_resolution` | Successful GET of a metadata/index page | `SliceIndex`, `DownloadPackageSlice` (npm), proxy `serveNonArtifact` |

**ECS fields per event**:
```json
{
  "event.category": "file",
  "event.action": "artifact_upload | artifact_download | artifact_delete | artifact_resolution",
  "event.outcome": "success",
  "client.ip": "<from MDC>",
  "user.name": "<from MDC or auth context>",
  "package.name": "<artifact name>",
  "package.version": "<version if parseable>",
  "repository.name": "<repo name>",
  "repository.type": "<maven | npm | pypi | docker | ...>",
  "file.name": "<filename>",
  "file.size": "<bytes, for upload/download>",
  "trace.id": "<from MDC>"
}
```

**Implementation pattern** — single static helper:
```java
public final class AuditLogger {
    private static final String LOGGER = "artifact.audit";

    public static void upload(String repoType, String repoName,
                              String packageName, String version,
                              String filename, long size) {
        EcsLogger.info(LOGGER)
            .message("Artifact uploaded")
            .field("event.category", "file")
            .field("event.action", "artifact_upload")
            .field("event.outcome", "success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("package.version", version)
            .field("file.name", filename)
            .field("file.size", size)
            .log();
        // client.ip, user.name, trace.id come from MDC automatically
    }

    public static void download(...) { /* same pattern */ }
    public static void delete(...) { /* same pattern */ }
    public static void resolution(...) { /* same pattern */ }
}
```

**Integration points**:
- `SliceUpload.response()` — after successful storage save, call `AuditLogger.upload()`
- `SliceDownload.response()` — after successful content stream, call `AuditLogger.download()`
- `SliceDelete.response()` — extract user from headers BEFORE delete, call `AuditLogger.delete()` after
- `EcsLoggingSlice` — already sets MDC (`client.ip`, `user.name`, `trace.id`) before any slice runs, so the audit logger inherits them
- Proxy paths: `ProxySlice.serveArtifact` (cache hit + remote fetch), `ProxySlice.serveNonArtifact` (resolution)
- Each adapter's download/upload handler where `repoType` is known

**NOT in the DB**: at 1000 req/s, DB inserts per download would saturate the connection pool. Logs → Elastic is the right path for high-volume audit. The existing `audit_log` table stays for admin operations (user/role/settings changes).

**log4j2.xml addition**:
```xml
<Logger name="artifact.audit" level="info" additivity="false">
    <AppenderRef ref="Console"/>
</Logger>
```

### Files to change
- New: `pantera-core/.../audit/AuditLogger.java`
- `pantera-core/.../SliceUpload.java` — add audit call
- `pantera-core/.../SliceDownload.java` — add audit call
- `pantera-core/.../SliceDelete.java` — extract user, add audit call
- `pypi-adapter/.../ProxySlice.java` — add audit in serveArtifact + serveNonArtifact
- `npm-adapter/.../DownloadPackageSlice.java` — add resolution audit
- Other adapter proxies — same pattern
- `pantera-main/src/main/resources/log4j2.xml` — add `artifact.audit` logger

---

## 3. Auth Failure Log Level Reclassification

### Problem
`AuthFromKeycloak` and `OktaOidcClient` log ALL authentication failures at ERROR level — including wrong passwords, which are expected and high-volume. This triggers false alerts at the SRE/security team's error-rate thresholds.

### Design

**Principle**: ERROR = system is broken and needs human attention. WARN = expected failure that's worth tracking. INFO = lifecycle event.

**Changes by file**:

**`AuthFromKeycloak.java`** (line 47-64):
- Current: catches all `Throwable`, logs ERROR.
- Fix: catch `org.keycloak.authorization.client.util.HttpResponseException` separately. If HTTP status is 401/403 → WARN ("SSO credentials rejected"). Any other exception (IOException, timeout, 5xx) → ERROR ("Keycloak system error").

**`AuthFromOkta.java`** (lines 47-64):
- Current: catches InterruptedException → ERROR, IOException → ERROR.
- Fix: InterruptedException stays ERROR (thread interrupt = system issue). IOException → check if it wraps a 401 response: if yes → WARN; if network/timeout → ERROR.

**`OktaOidcClient.java`** (15+ ERROR calls):
- Reclassify each by root cause:
  - Token exchange got 401/403 → WARN
  - Network/timeout → ERROR
  - Token validation failed (malformed token) → WARN
  - MFA challenge → INFO (expected flow)
  - Session token expired → WARN

**`LoggingAuth.java`** (line 41):
- Current: WARN for all failures.
- Keep WARN. This is the outer wrapper — individual providers already log at the right level.

**`UnifiedJwtAuthHandler.java`**:
- Token revoked → INFO (admin action, expected lifecycle)
- User disabled → WARN (admin action, worth tracking)
- JTI not found → WARN (possible token reuse or DB inconsistency)

**SRE alert configuration guidance** (document, don't implement):
- Alert on ERROR in `com.auto1.pantera.auth` — these are system failures.
- Dashboard WARN count for auth — this is the "wrong password rate" metric.
- Don't alert on WARN — it's operational visibility, not an incident.

### Files to change
- `pantera-main/.../auth/AuthFromKeycloak.java`
- `pantera-main/.../auth/AuthFromOkta.java`
- `pantera-main/.../auth/OktaOidcClient.java`
- `pantera-main/.../auth/UnifiedJwtAuthHandler.java`
- `pantera-main/.../auth/LoggingAuth.java` (verify, likely no change)
- `pantera-main/.../auth/AuthFromStorage.java` (verify, likely no change)

---

## 4. Distributed Tracing (B3 / W3C / SRE Convention)

### Problem
SRE requires `trace.id`, `span.id`, `span.parent.id` in all log entries and propagated in all upstream requests. Current implementation has only `trace.id`. The W3C `traceparent` header is parsed but `span.id` is ignored. UI traces (Elastic APM RUM) are not linked to backend.

### Design

**Format**: `/[\da-f]{16}/` for all three IDs (per SRE convention and B3 spec).

**SRE error code**: `SRE2042 Malformed|Missing trace|span.id [$value] for user-agent [$ua]` — logged at WARN when a malformed ID is received and regenerated.

#### 4.1 TraceContext Enhancement

Extend the existing `TraceContext` class with span support:

```java
public final class TraceContext {
    // Existing
    public static final String TRACE_ID = "trace.id";     // 16 hex chars
    // New
    public static final String SPAN_ID = "span.id";       // 16 hex chars
    public static final String PARENT_SPAN_ID = "span.parent.id"; // 16 hex chars or empty

    // B3 headers (openzipkin standard)
    public static final String B3_TRACE_ID = "X-B3-TraceId";
    public static final String B3_SPAN_ID = "X-B3-SpanId";
    public static final String B3_PARENT_SPAN_ID = "X-B3-ParentSpanId";
    // Single-header B3 format: {trace-id}-{span-id}-{sampling}-{parent-span-id}
    public static final String B3_SINGLE = "b3";
    // W3C (already partially supported)
    public static final String TRACEPARENT = "traceparent";
}
```

**Extraction precedence** (per request):
1. `b3` single header → parse all four fields
2. `X-B3-TraceId` + `X-B3-SpanId` + `X-B3-ParentSpanId` → individual B3 headers
3. `traceparent` → W3C format `00-{trace-id}-{span-id}-{flags}`
4. `X-Trace-Id` / `X-Request-Id` → trace.id only (legacy)
5. Generate fresh trace.id + span.id if missing

**Validation** (per SRE requirements):
- Format: `/[\da-f]{16}/` for all IDs
- If malformed: regenerate and log `SRE2042 Malformed trace.id [{value}] for user-agent [{ua}]` at WARN
- If missing: generate and log `SRE2042 Missing span.id for user-agent [{ua}]` at WARN (only for span.id; trace.id missing is normal for first hop)

**Span lifecycle per request**:
1. Extract trace.id from incoming headers (or generate)
2. Incoming span.id becomes span.parent.id
3. Generate NEW span.id for this request
4. Set all three in MDC → automatically emitted in every log line via ECS layout
5. On outgoing upstream calls (proxy fetch, Keycloak, Okta), set B3 + W3C headers

#### 4.2 MDC Fields (EcsMdc)

Add to `EcsMdc.java`:
```java
public static final String SPAN_ID = "span.id";
public static final String PARENT_SPAN_ID = "span.parent.id";
```

Log4j2 ECS layout picks up all MDC fields automatically — no log4j2.xml change needed.

#### 4.3 EcsLoggingSlice Update

```java
// Before (current):
MDC.put(EcsMdc.TRACE_ID, traceId);

// After:
final TraceIds ids = TraceContext.extractOrGenerate(headers, userAgent);
MDC.put(EcsMdc.TRACE_ID, ids.traceId());
MDC.put(EcsMdc.SPAN_ID, ids.spanId());
if (ids.parentSpanId() != null) {
    MDC.put(EcsMdc.PARENT_SPAN_ID, ids.parentSpanId());
}
```

Cleanup in the `finally` block already clears all MDC entries.

#### 4.4 Upstream Propagation

Every outgoing HTTP call must carry the trace context. Pantera makes upstream calls in:
- `ProxySlice` (all adapters) — fetch from upstream registry
- `AuthFromKeycloak` — Keycloak token exchange
- `AuthFromOkta` / `OktaOidcClient` — Okta OIDC flow
- `AuthHandler.callbackEndpoint` — SSO token exchange

For each, add headers to outgoing requests:
```
X-B3-TraceId: {trace.id from MDC}
X-B3-SpanId: {new 16-hex for this outgoing call}
X-B3-ParentSpanId: {span.id from MDC — the current request's span}
traceparent: 00-{trace.id}-{new span.id}-01
```

**Implementation**: a shared `TraceHeaders.inject(headers)` utility that reads MDC and adds the headers.

#### 4.5 UI → Backend Linkage

**Axios interceptor** (`pantera-ui/src/api/client.ts`):
- Request interceptor reads the current Elastic APM transaction (if APM is active) and sets `traceparent` header.
- If APM is not active (disabled or not configured), generate a random trace.id + span.id and set `traceparent` anyway — this ensures even non-APM deployments get correlated UI → backend logs.

```typescript
apiClient.interceptors.request.use((config) => {
    const apm = (window as any).__ELASTIC_APM;
    if (apm) {
        const tx = apm.getCurrentTransaction();
        if (tx) {
            config.headers.traceparent = tx.traceparent;
            return config;
        }
    }
    // Fallback: generate our own traceparent
    const traceId = crypto.randomUUID().replace(/-/g, '').slice(0, 32);
    const spanId = crypto.randomUUID().replace(/-/g, '').slice(0, 16);
    config.headers.traceparent = `00-${traceId}-${spanId}-01`;
    return config;
});
```

#### 4.6 Response Header

Return `traceparent` in the HTTP response so callers (including the UI APM agent) can link their spans:
```java
// In EcsLoggingSlice, after computing span IDs:
response.headers().add("traceparent",
    String.format("00-%s-%s-01", traceId, spanId));
```

#### 4.7 Startup / CLI / Background Tasks

Per SRE: "The application should have tracing variables even before processing HTTP requests."

- `VertxMain.start()`: generate trace.id + span.id, set in MDC before any initialization code runs.
- `QuartzService` jobs: generate fresh trace.id + span.id per job execution.
- `MetadataEventQueues` consumer: generate fresh trace.id + span.id per batch.
- Pantera-backfill CLI: generate at startup, propagate to all DB operations.

### Files to change
- `pantera-core/.../http/trace/TraceContext.java` — add span support, B3 parsing, validation, SRE2042 logging
- `pantera-core/.../http/log/EcsMdc.java` — add SPAN_ID, PARENT_SPAN_ID
- `pantera-core/.../http/slice/EcsLoggingSlice.java` — use new TraceContext, set MDC, add response header
- New: `pantera-core/.../http/trace/TraceHeaders.java` — inject trace headers into outgoing requests
- `pantera-main/.../VertxMain.java` — startup trace context
- `pantera-main/.../auth/AuthFromKeycloak.java` — inject trace headers
- `pantera-main/.../auth/OktaOidcClient.java` — inject trace headers
- `pantera-main/.../auth/AuthHandler.java` — inject trace headers in SSO callback
- All proxy adapter slices — inject trace headers in upstream fetch
- `pantera-main/.../scheduling/QuartzService.java` — per-job trace context
- `pantera-ui/src/api/client.ts` — request interceptor with traceparent

---

## 5. `url.original` Verification

### Current State
Already implemented at `EcsLogEvent.java:159-170`. Value is `line.uri().toString()` (full path + query string), sanitized via `LogSanitizer.sanitizeUrl()`. Set in `EcsLoggingSlice` line 128.

### Verification tasks
- Confirm both ports (80 and 8086) emit `url.original` in production logs.
- Confirm the ECS layout in log4j2.xml includes the field (it should — `EcsLayout` emits all fields set on the log event).
- Confirm `url.query` is also set (it is, at EcsLogEvent line 173-179).
- Check that `url.original` doesn't double-encode or strip the query string.
- Add a test that asserts `url.original` includes path + query for a representative request.

### Files to verify (no changes expected)
- `pantera-core/.../http/log/EcsLogEvent.java` — urlOriginal method
- `pantera-core/.../http/slice/EcsLoggingSlice.java` — where it's called
- `pantera-main/src/main/resources/log4j2.xml` — ECS layout config

---

## Cross-cutting: Test Strategy

| Area | Test Type | What |
|---|---|---|
| Proxy Protocol | Integration | Vert.x server with PROXY protocol header → verify `remoteAddress()` is real IP |
| Audit logging | Unit | `AuditLogger` emits correct ECS fields; verify via Log4j test appender |
| Audit logging | Integration | Upload/download/delete through handler → verify `artifact.audit` log emitted |
| Auth levels | Unit | `AuthFromKeycloak` with 401 response → WARN; with IOException → ERROR |
| Tracing | Unit | `TraceContext.extractOrGenerate()` with B3, W3C, malformed, missing inputs |
| Tracing | Unit | SRE2042 log emitted for malformed trace.id |
| Tracing | Integration | Full HTTP request → verify trace.id, span.id, span.parent.id in log output + response header |
| url.original | Unit | `EcsLogEvent.urlOriginal` includes path + query; verify in `EcsSchemaValidationTest` |

---

## Rollout Order

All five ship together in a single release. Implementation order within the branch:

1. **Tracing** (largest, most cross-cutting — do first to avoid rework)
2. **Audit logging** (depends on MDC fields from tracing)
3. **Auth log levels** (independent, small)
4. **Proxy Protocol** (independent, small)
5. **url.original verification** (verify-only, last)

---

## Out of Scope

- Full OpenTelemetry SDK / OTEL collector — deferred to future. The B3 + W3C header propagation in this spec is forward-compatible: when OTEL is added later, it reads the same headers.
- Elastic APM Java agent on the backend — not needed for the SRE requirements. Can be added later.
- `audit_log` database table changes — artifact audit goes to structured logs, not DB.
- Rate limiting on audit logs — at 1000 req/s, ECS JSON is ~500 bytes per event = 500 KB/s of audit log volume. Manageable.
