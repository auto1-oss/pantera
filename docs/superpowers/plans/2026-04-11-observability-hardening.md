# Observability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 5 observability gaps — Proxy Protocol v2, artifact audit logging, auth log level reclassification, distributed tracing (B3/W3C/SRE), and url.original verification.

**Architecture:** Extend the existing ECS logging stack (EcsLogger + Log4j2 EcsLayout + MDC) with span tracking, a dedicated audit logger, and proxy protocol support. No new dependencies except `netty-codec-haproxy` (already transitive). The UI Axios client gets a `traceparent` request interceptor.

**Tech Stack:** Java 21, Vert.x 4.5.x, Log4j2 + Elastic ECS Layout, Netty HAProxy codec, Vue 3 + Axios, Elastic APM RUM (optional).

**Spec:** `docs/superpowers/specs/2026-04-11-observability-hardening-design.md`

---

## File Map

### New files
| File | Purpose |
|---|---|
| `pantera-core/src/main/java/com/auto1/pantera/http/trace/SpanContext.java` | Span ID generation, B3/W3C parsing, SRE2042 validation |
| `pantera-core/src/main/java/com/auto1/pantera/http/trace/TraceHeaders.java` | Inject trace headers into outgoing HTTP requests |
| `pantera-core/src/main/java/com/auto1/pantera/audit/AuditLogger.java` | Static helper for artifact audit events |
| `pantera-core/src/test/java/com/auto1/pantera/http/trace/SpanContextTest.java` | Unit tests for span parsing + SRE2042 |
| `pantera-core/src/test/java/com/auto1/pantera/audit/AuditLoggerTest.java` | Unit tests for audit ECS field emission |
| `pantera-main/src/test/java/com/auto1/pantera/auth/AuthLogLevelTest.java` | Unit tests for auth reclassification |

### Modified files
| File | Change |
|---|---|
| `pantera-core/.../http/log/EcsMdc.java` | Add `SPAN_ID`, `PARENT_SPAN_ID` constants |
| `pantera-core/.../http/slice/EcsLoggingSlice.java` | Use SpanContext for MDC, add `traceparent` response header, cleanup new MDC keys |
| `pantera-core/.../http/slice/SliceDownload.java` | Add `AuditLogger.download()` call |
| `pantera-core/.../http/slice/SliceUpload.java` | Add `AuditLogger.upload()` call |
| `pantera-core/.../http/slice/SliceDelete.java` | Extract user from headers, add `AuditLogger.delete()` call |
| `pantera-main/.../auth/AuthFromKeycloak.java` | Split catch: 401/403 → WARN, other → ERROR; inject trace headers |
| `pantera-main/.../auth/AuthFromOkta.java` | Same log level split |
| `pantera-main/.../auth/OktaOidcClient.java` | Reclassify ~15 ERROR calls |
| `pantera-main/.../auth/UnifiedJwtAuthHandler.java` | Token revoked → INFO |
| `pantera-main/.../settings/YamlSettings.java` | Read `meta.proxy-protocol` boolean |
| `pantera-main/.../VertxMain.java` | Pass proxy-protocol flag; startup trace context |
| `vertx-server/.../VertxSliceServer.java` | Accept + apply `useProxyProtocol` option |
| `pantera-main/src/main/resources/log4j2.xml` | Add `artifact.audit` logger |
| `pantera-ui/src/api/client.ts` | Add `traceparent` request interceptor |
| Proxy adapter slices (pypi, npm, maven, etc.) | Inject trace headers on upstream fetch; add audit calls |

---

## Task 1: SpanContext — B3/W3C Parsing + SRE2042 Validation

**Files:**
- Create: `pantera-core/src/main/java/com/auto1/pantera/http/trace/SpanContext.java`
- Create: `pantera-core/src/test/java/com/auto1/pantera/http/trace/SpanContextTest.java`
- Modify: `pantera-core/src/main/java/com/auto1/pantera/http/log/EcsMdc.java`

### Step 1: Write tests for SpanContext

- [ ] Create `SpanContextTest.java`:

```java
package com.auto1.pantera.http.trace;

import com.auto1.pantera.http.Headers;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpanContextTest {

    private static final String HEX16 = "[\\da-f]{16}";

    // --- B3 single header ---

    @Test
    void parsesB3SingleHeader() {
        final Headers headers = Headers.from(
            "b3", "463ac35c9f6413ad-0020000000000001-1-00f067aa0ba902b7"
        );
        final SpanContext ctx = SpanContext.extract(headers, "test-agent");
        assertEquals("463ac35c9f6413ad", ctx.traceId());
        assertEquals("00f067aa0ba902b7", ctx.parentSpanId());
        assertTrue(ctx.spanId().matches(HEX16), "span.id must be newly generated");
        assertNotEquals("0020000000000001", ctx.spanId(),
            "span.id must NOT be the incoming span — that becomes parent");
    }

    @Test
    void parsesB3SingleHeaderWithoutParent() {
        final Headers headers = Headers.from(
            "b3", "463ac35c9f6413ad-0020000000000001-1"
        );
        final SpanContext ctx = SpanContext.extract(headers, "test-agent");
        assertEquals("463ac35c9f6413ad", ctx.traceId());
        assertEquals("0020000000000001", ctx.parentSpanId());
        assertTrue(ctx.spanId().matches(HEX16));
    }

    // --- B3 multi-header ---

    @Test
    void parsesB3MultiHeaders() {
        final Headers headers = Headers.from(
            "X-B3-TraceId", "463ac35c9f6413ad",
            "X-B3-SpanId", "0020000000000001",
            "X-B3-ParentSpanId", "00f067aa0ba902b7"
        );
        final SpanContext ctx = SpanContext.extract(headers, "test-agent");
        assertEquals("463ac35c9f6413ad", ctx.traceId());
        assertEquals("0020000000000001", ctx.parentSpanId());
        assertEquals("00f067aa0ba902b7", ctx.parentSpanId());
    }

    // --- W3C traceparent ---

    @Test
    void parsesW3cTraceparent() {
        // traceparent: version-traceId-spanId-flags
        // traceId is 32 hex in W3C; we take the last 16 per SRE convention
        final Headers headers = Headers.from(
            "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        );
        final SpanContext ctx = SpanContext.extract(headers, "test-agent");
        assertEquals("a3ce929d0e0e4736", ctx.traceId(),
            "W3C 32-hex trace-id truncated to last 16 hex");
        assertEquals("00f067aa0ba902b7", ctx.parentSpanId());
        assertTrue(ctx.spanId().matches(HEX16));
    }

    // --- Fallback to legacy headers ---

    @Test
    void fallsBackToXRequestId() {
        final Headers headers = Headers.from("X-Request-Id", "abc123def456abcd");
        final SpanContext ctx = SpanContext.extract(headers, "test-agent");
        assertEquals("abc123def456abcd", ctx.traceId());
        assertTrue(ctx.spanId().matches(HEX16));
        assertNull(ctx.parentSpanId());
    }

    // --- Generation when no headers ---

    @Test
    void generatesWhenNoHeaders() {
        final SpanContext ctx = SpanContext.extract(Headers.EMPTY, "test-agent");
        assertTrue(ctx.traceId().matches(HEX16));
        assertTrue(ctx.spanId().matches(HEX16));
        assertNull(ctx.parentSpanId());
    }

    // --- SRE2042 validation ---

    @Test
    void regeneratesMalformedTraceId() {
        final Headers headers = Headers.from("X-B3-TraceId", "krekeke");
        final SpanContext ctx = SpanContext.extract(headers, "Guzzle/6.6.6");
        // Malformed → regenerated
        assertTrue(ctx.traceId().matches(HEX16));
        assertNotEquals("krekeke", ctx.traceId());
    }

    @Test
    void regeneratesTooShortTraceId() {
        final Headers headers = Headers.from("X-B3-TraceId", "abc");
        final SpanContext ctx = SpanContext.extract(headers, "curl");
        assertTrue(ctx.traceId().matches(HEX16));
    }

    // --- Format validation ---

    @Test
    void isValidHex16AcceptsGood() {
        assertTrue(SpanContext.isValidId("463ac35c9f6413ad"));
        assertTrue(SpanContext.isValidId("0000000000000001"));
    }

    @Test
    void isValidHex16RejectsBad() {
        assertFalse(SpanContext.isValidId(null));
        assertFalse(SpanContext.isValidId(""));
        assertFalse(SpanContext.isValidId("abc"));
        assertFalse(SpanContext.isValidId("ZZZZZZZZZZZZZZZZ"));
        assertFalse(SpanContext.isValidId("463ac35c9f6413ad00")); // 18 chars
    }

    // --- traceparent formatting ---

    @Test
    void formatsTraceparentHeader() {
        final SpanContext ctx = SpanContext.extract(Headers.EMPTY, "test");
        final String tp = ctx.toTraceparent();
        assertTrue(tp.matches("00-[\\da-f]{16}-[\\da-f]{16}-01"),
            "traceparent must be 00-{traceId}-{spanId}-01, got: " + tp);
    }

    // --- B3 single formatting ---

    @Test
    void formatsB3SingleHeader() {
        final SpanContext ctx = SpanContext.extract(Headers.EMPTY, "test");
        final String b3 = ctx.toB3Single();
        // {traceId}-{spanId}-1 (no parent since generated fresh)
        assertTrue(b3.matches("[\\da-f]{16}-[\\da-f]{16}-1"),
            "b3 single must be {traceId}-{spanId}-1, got: " + b3);
    }
}
```

- [ ] Run: `mvn test -pl pantera-core -Dtest="SpanContextTest"` — expect FAIL (class doesn't exist)

### Step 2: Add MDC constants

- [ ] Edit `pantera-core/src/main/java/com/auto1/pantera/http/log/EcsMdc.java`:

```java
public final class EcsMdc {
    public static final String TRACE_ID = "trace.id";
    public static final String SPAN_ID = "span.id";
    public static final String PARENT_SPAN_ID = "span.parent.id";
    public static final String CLIENT_IP = "client.ip";
    public static final String USER_NAME = "user.name";

    private EcsMdc() {
    }
}
```

### Step 3: Implement SpanContext

- [ ] Create `pantera-core/src/main/java/com/auto1/pantera/http/trace/SpanContext.java`:

```java
package com.auto1.pantera.http.trace;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsLogger;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Extracts or generates trace.id, span.id, span.parent.id per
 * B3 (openzipkin) and W3C Trace Context standards.
 *
 * ID format: /[\da-f]{16}/ (SRE convention).
 *
 * Extraction precedence: b3 single → B3 multi → traceparent → X-Trace-Id → X-Request-Id → generate.
 */
public final class SpanContext {

    private static final Pattern HEX16 = Pattern.compile("[\\da-f]{16}");
    private static final SecureRandom RNG = new SecureRandom();
    private static final String SRE_CODE = "SRE2042";

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;

    private SpanContext(String traceId, String spanId, String parentSpanId) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
    }

    public String traceId() { return this.traceId; }
    public String spanId() { return this.spanId; }
    public String parentSpanId() { return this.parentSpanId; }

    /** Format as W3C traceparent: 00-{traceId}-{spanId}-01 */
    public String toTraceparent() {
        return String.format("00-%s-%s-01", this.traceId, this.spanId);
    }

    /** Format as B3 single: {traceId}-{spanId}-1[-{parentSpanId}] */
    public String toB3Single() {
        if (this.parentSpanId != null) {
            return String.format("%s-%s-1-%s", this.traceId, this.spanId, this.parentSpanId);
        }
        return String.format("%s-%s-1", this.traceId, this.spanId);
    }

    /** Validate a 16-hex-char ID per SRE convention. */
    public static boolean isValidId(final String id) {
        return id != null && HEX16.matcher(id).matches();
    }

    /** Generate a random 16-hex-char ID. */
    public static String generateId() {
        final byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        final StringBuilder sb = new StringBuilder(16);
        for (final byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Extract trace context from request headers, or generate fresh IDs.
     *
     * The incoming span.id becomes span.parent.id; a new span.id is
     * generated for this request.
     *
     * Malformed IDs are regenerated and logged per SRE2042.
     */
    public static SpanContext extract(final Headers headers, final String userAgent) {
        String traceId = null;
        String incomingSpanId = null;
        String parentSpanId = null;
        final String ua = userAgent != null ? userAgent : "unknown";

        // 1. b3 single header: {traceId}-{spanId}-{sampling}[-{parentSpanId}]
        final String b3 = firstHeader(headers, "b3");
        if (b3 != null && !b3.isEmpty()) {
            final String[] parts = b3.split("-");
            if (parts.length >= 2) {
                traceId = validateOrRegenerate(parts[0], "trace.id", ua);
                incomingSpanId = parts[1];
                if (parts.length >= 4) {
                    parentSpanId = validateOrRegenerate(parts[3], "span.parent.id", ua);
                }
            }
        }

        // 2. B3 multi-header
        if (traceId == null) {
            final String b3Trace = firstHeader(headers, "X-B3-TraceId");
            if (b3Trace != null && !b3Trace.isEmpty()) {
                traceId = validateOrRegenerate(b3Trace, "trace.id", ua);
                incomingSpanId = firstHeader(headers, "X-B3-SpanId");
                parentSpanId = firstHeader(headers, "X-B3-ParentSpanId");
                if (parentSpanId != null && !isValidId(parentSpanId)) {
                    logSre2042("Malformed", "span.parent.id", parentSpanId, ua);
                    parentSpanId = null;
                }
            }
        }

        // 3. W3C traceparent: 00-{traceId32}-{spanId16}-{flags}
        if (traceId == null) {
            final String tp = firstHeader(headers, "traceparent");
            if (tp != null && !tp.isEmpty()) {
                final String[] parts = tp.split("-");
                if (parts.length >= 3) {
                    // W3C trace-id is 32 hex; SRE convention is 16 hex.
                    // Take the last 16 chars (low-order bits).
                    final String w3cTraceId = parts[1];
                    if (w3cTraceId.length() == 32) {
                        traceId = validateOrRegenerate(
                            w3cTraceId.substring(16), "trace.id", ua
                        );
                    } else {
                        traceId = validateOrRegenerate(w3cTraceId, "trace.id", ua);
                    }
                    incomingSpanId = parts[2];
                }
            }
        }

        // 4. Legacy headers
        if (traceId == null) {
            final String xTrace = firstHeader(headers, "X-Trace-Id");
            if (xTrace != null && !xTrace.isEmpty()) {
                traceId = validateOrRegenerate(xTrace, "trace.id", ua);
            }
        }
        if (traceId == null) {
            final String xReq = firstHeader(headers, "X-Request-Id");
            if (xReq != null && !xReq.isEmpty()) {
                traceId = validateOrRegenerate(xReq, "trace.id", ua);
            }
        }

        // 5. Generate if still missing
        if (traceId == null) {
            traceId = generateId();
        }

        // Incoming span becomes parent; generate new span for this request
        if (incomingSpanId != null && isValidId(incomingSpanId)) {
            if (parentSpanId == null) {
                parentSpanId = incomingSpanId;
            }
        }
        final String newSpanId = generateId();

        return new SpanContext(traceId, newSpanId, parentSpanId);
    }

    private static String validateOrRegenerate(
        final String value, final String field, final String ua
    ) {
        if (isValidId(value)) {
            return value;
        }
        logSre2042(
            value == null || value.isEmpty() ? "Missing" : "Malformed",
            field, value, ua
        );
        return generateId();
    }

    private static void logSre2042(
        final String kind, final String field, final String value, final String ua
    ) {
        EcsLogger.warn("com.auto1.pantera.trace")
            .message(String.format(
                "%s %s %s [%s] for user-agent [%s]",
                SRE_CODE, kind, field,
                value != null ? value : "", ua
            ))
            .field("event.category", "web")
            .field("event.action", "trace_validation")
            .log();
    }

    private static String firstHeader(final Headers headers, final String name) {
        for (final var h : headers.find(name)) {
            final String v = h.getValue();
            if (v != null && !v.isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }
}
```

- [ ] Run: `mvn test -pl pantera-core -Dtest="SpanContextTest"` — expect PASS

- [ ] Commit: `feat(trace): SpanContext with B3/W3C parsing and SRE2042 validation`

---

## Task 2: TraceHeaders — Outgoing Request Propagation

**Files:**
- Create: `pantera-core/src/main/java/com/auto1/pantera/http/trace/TraceHeaders.java`

- [ ] Create `TraceHeaders.java`:

```java
package com.auto1.pantera.http.trace;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsMdc;
import org.slf4j.MDC;

/**
 * Injects trace context headers into outgoing HTTP requests.
 *
 * Reads trace.id, span.id from MDC (set by EcsLoggingSlice per request)
 * and adds B3 + W3C headers for upstream propagation.
 */
public final class TraceHeaders {

    private TraceHeaders() {
    }

    /**
     * Add trace propagation headers to an existing Headers set.
     * For outgoing calls, generates a new child span.id and uses the
     * current span.id as parent.
     */
    public static Headers inject(final Headers existing) {
        final String traceId = MDC.get(EcsMdc.TRACE_ID);
        final String currentSpan = MDC.get(EcsMdc.SPAN_ID);
        if (traceId == null || currentSpan == null) {
            return existing;
        }
        final String childSpan = SpanContext.generateId();
        return existing
            .add(new Header("X-B3-TraceId", traceId))
            .add(new Header("X-B3-SpanId", childSpan))
            .add(new Header("X-B3-ParentSpanId", currentSpan))
            .add(new Header("traceparent",
                String.format("00-%s-%s-01", traceId, childSpan)));
    }

    /**
     * Build trace headers for java.net.http.HttpRequest (used by AuthHandler SSO callback).
     * Returns a flat array of [key, value, key, value, ...] pairs.
     */
    public static String[] httpClientHeaders() {
        final String traceId = MDC.get(EcsMdc.TRACE_ID);
        final String currentSpan = MDC.get(EcsMdc.SPAN_ID);
        if (traceId == null || currentSpan == null) {
            return new String[0];
        }
        final String childSpan = SpanContext.generateId();
        return new String[]{
            "X-B3-TraceId", traceId,
            "X-B3-SpanId", childSpan,
            "X-B3-ParentSpanId", currentSpan,
            "traceparent", String.format("00-%s-%s-01", traceId, childSpan)
        };
    }
}
```

- [ ] Commit: `feat(trace): TraceHeaders for outgoing request propagation`

---

## Task 3: EcsLoggingSlice — Wire SpanContext + Response Header

**Files:**
- Modify: `pantera-core/src/main/java/com/auto1/pantera/http/slice/EcsLoggingSlice.java`

- [ ] Replace the trace ID setup block (lines ~88-106) with SpanContext:

```java
// OLD: manual trace ID extraction from ~5 header checks + UUID fallback
// NEW: SpanContext handles B3/W3C/legacy/generation with SRE2042 validation

final String userAgent = headers.find("user-agent").iterator().hasNext()
    ? headers.find("user-agent").iterator().next().getValue() : "unknown";
final SpanContext span = SpanContext.extract(headers, userAgent);

MDC.put(EcsMdc.TRACE_ID, span.traceId());
MDC.put(EcsMdc.SPAN_ID, span.spanId());
if (span.parentSpanId() != null) {
    MDC.put(EcsMdc.PARENT_SPAN_ID, span.parentSpanId());
}
```

- [ ] In the response `thenApply` block, add `traceparent` response header:

```java
// After building logEvent, before returning response:
final Response traced = ResponseBuilder.from(response)
    .header("traceparent", span.toTraceparent())
    .build();
return traced;
```

- [ ] In the cleanup `whenComplete` block (~lines 161-166), add new MDC removals:

```java
MDC.remove(EcsMdc.SPAN_ID);
MDC.remove(EcsMdc.PARENT_SPAN_ID);
```

- [ ] Run: `mvn test -pl pantera-core` — expect PASS (existing tests still work)

- [ ] Commit: `feat(trace): wire SpanContext into EcsLoggingSlice, add traceparent response header`

---

## Task 4: AuditLogger — Artifact Audit Events

**Files:**
- Create: `pantera-core/src/main/java/com/auto1/pantera/audit/AuditLogger.java`
- Create: `pantera-core/src/test/java/com/auto1/pantera/audit/AuditLoggerTest.java`
- Modify: `pantera-main/src/main/resources/log4j2.xml`

- [ ] Create `AuditLogger.java`:

```java
package com.auto1.pantera.audit;

import com.auto1.pantera.http.log.EcsLogger;

/**
 * Structured audit logging for artifact operations.
 *
 * Emits INFO-level logs to the "artifact.audit" logger so Elastic
 * indexes them as a dedicated data stream. MDC fields (client.ip,
 * user.name, trace.id, span.id) are automatically included by the
 * Log4j ECS layout.
 */
public final class AuditLogger {

    private static final String LOGGER = "artifact.audit";

    private AuditLogger() {
    }

    public static void upload(final String repoType, final String repoName,
                              final String packageName, final String version,
                              final String filename, final long size) {
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
    }

    public static void download(final String repoType, final String repoName,
                                final String packageName, final String version,
                                final String filename, final long size) {
        EcsLogger.info(LOGGER)
            .message("Artifact downloaded")
            .field("event.category", "file")
            .field("event.action", "artifact_download")
            .field("event.outcome", "success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("package.version", version)
            .field("file.name", filename)
            .field("file.size", size)
            .log();
    }

    public static void delete(final String repoType, final String repoName,
                              final String packageName, final String version,
                              final String filename) {
        EcsLogger.info(LOGGER)
            .message("Artifact deleted")
            .field("event.category", "file")
            .field("event.action", "artifact_delete")
            .field("event.outcome", "success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("package.version", version)
            .field("file.name", filename)
            .log();
    }

    public static void resolution(final String repoType, final String repoName,
                                  final String packageName) {
        EcsLogger.info(LOGGER)
            .message("Artifact metadata resolved")
            .field("event.category", "file")
            .field("event.action", "artifact_resolution")
            .field("event.outcome", "success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .log();
    }
}
```

- [ ] Add `artifact.audit` logger to `log4j2.xml` (after the `http.access` logger):

```xml
<Logger name="artifact.audit" level="info" additivity="false">
    <AppenderRef ref="AsyncConsole"/>
</Logger>
```

- [ ] Write `AuditLoggerTest.java` — verify EcsLogger is called with correct fields (use a Log4j test appender or verify via ThreadContext assertions).

- [ ] Run: `mvn test -pl pantera-core -Dtest="AuditLoggerTest"` — expect PASS

- [ ] Commit: `feat(audit): AuditLogger for artifact upload/download/delete/resolution`

---

## Task 5: Wire Audit Logging into SliceUpload / SliceDownload / SliceDelete

**Files:**
- Modify: `pantera-core/src/main/java/com/auto1/pantera/http/slice/SliceUpload.java`
- Modify: `pantera-core/src/main/java/com/auto1/pantera/http/slice/SliceDownload.java`
- Modify: `pantera-core/src/main/java/com/auto1/pantera/http/slice/SliceDelete.java`

- [ ] **SliceUpload** — after the existing event queue code (line ~98), add:

```java
// After: this.events.get().addUploadEventByKey(key, size, headers)
// Add audit log (MDC has client.ip, user.name, trace.id from EcsLoggingSlice):
final String filename = key.string().contains("/")
    ? key.string().substring(key.string().lastIndexOf('/') + 1) : key.string();
AuditLogger.upload(
    this.repoType, this.repoName,
    filename, "", filename, size
);
```

Note: `SliceUpload` needs `repoType` and `repoName` — add constructor parameters and pass from callers. If too invasive, use the MDC `repository.name` field that `EcsLoggingSlice` sets, or extract from the key path.

- [ ] **SliceDownload** — after the successful `exists=true` branch (line ~76), add:

```java
// After OptimizedStorageCache.optimizedValue returns:
.thenApply(content -> {
    // Audit log the download
    final String filename = key.string().contains("/")
        ? key.string().substring(key.string().lastIndexOf('/') + 1) : key.string();
    content.size().ifPresent(size ->
        AuditLogger.download("", "", filename, "", filename, size)
    );
    return ResponseBuilder.ok()
        .header(new ContentFileName(line.uri()))
        .body(content)
        .build();
})
```

- [ ] **SliceDelete** — extract user from headers BEFORE the delete, add audit AFTER:

```java
// Before storage.delete:
final String user = new Login(headers).getValue();
final String filename = key.string().contains("/")
    ? key.string().substring(key.string().lastIndexOf('/') + 1) : key.string();

// After successful delete, before thenApply:
AuditLogger.delete("", "", filename, "", filename);
```

- [ ] Run: `mvn test -pl pantera-core` — expect PASS

- [ ] Commit: `feat(audit): wire audit logging into SliceUpload/SliceDownload/SliceDelete`

---

## Task 6: Auth Log Level Reclassification

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/auth/AuthFromKeycloak.java`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/auth/AuthFromOkta.java`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/auth/OktaOidcClient.java`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/auth/UnifiedJwtAuthHandler.java`
- Create: `pantera-main/src/test/java/com/auto1/pantera/auth/AuthLogLevelTest.java`

- [ ] **AuthFromKeycloak** — replace the single `catch (Throwable)` with two catches:

```java
try {
    client.obtainAccessToken(username, password);
    res = Optional.of(new AuthUser(username, "keycloak"));
} catch (final javax.ws.rs.NotAuthorizedException | javax.ws.rs.ForbiddenException authErr) {
    // Wrong credentials — expected, high volume. WARN not ERROR.
    EcsLogger.warn("com.auto1.pantera.auth")
        .message("Keycloak credentials rejected for user '" + username + "'")
        .eventCategory("authentication")
        .eventAction("login")
        .eventOutcome("failure")
        .log();
    res = Optional.empty();
} catch (final Throwable err) {
    // System failure (network, timeout, Keycloak down). ERROR.
    EcsLogger.error("com.auto1.pantera.auth")
        .message("Keycloak system error for user '" + username + "'")
        .eventCategory("authentication")
        .eventAction("login")
        .eventOutcome("failure")
        .error(err)
        .log();
    res = Optional.empty();
}
```

Note: check exact Keycloak SDK exception class — may be `org.keycloak.authorization.client.util.HttpResponseException` with `getStatusCode()`. Adjust the catch accordingly. The key: HTTP 401/403 → WARN, everything else → ERROR.

- [ ] **AuthFromOkta** — same pattern: IOException wrapping a 401 response → WARN, network/timeout → ERROR.

- [ ] **OktaOidcClient** — reclassify the ~15 ERROR calls. For each, check if the failure is user-caused (wrong creds, expired session, invalid MFA) → WARN, or system-caused (network, parse error) → ERROR.

- [ ] **UnifiedJwtAuthHandler** — change "Token rejected: blocklisted" from WARN to INFO (admin revocation is a lifecycle event, not a failure).

- [ ] Write `AuthLogLevelTest.java` — verify the Keycloak 401 path logs at WARN using a test Log4j appender. Verify a simulated IOException logs at ERROR.

- [ ] Run: `mvn test -pl pantera-main -Dtest="AuthLogLevelTest,AuthFromDbTest,AuthHandlerTest"` — expect PASS

- [ ] Commit: `fix(auth): reclassify auth failures — wrong password is WARN, system errors stay ERROR`

---

## Task 7: Proxy Protocol v2

**Files:**
- Modify: `vertx-server/src/main/java/com/auto1/pantera/vertx/VertxSliceServer.java`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/settings/YamlSettings.java`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/VertxMain.java`

- [ ] **YamlSettings** — add `proxyProtocol()` method:

```java
// In the meta.http_server section (near parseRequestTimeout):
public boolean proxyProtocol() {
    final YamlMapping server = this.meta != null
        ? this.meta.yamlMapping("http_server") : null;
    if (server == null) {
        return false;
    }
    return "true".equalsIgnoreCase(server.string("proxy_protocol"));
}
```

Also add to `Settings` interface if it doesn't already have a `proxyProtocol()` method.

- [ ] **VertxSliceServer** — in the full constructor (line ~213-239), accept a boolean `proxyProtocol` parameter. When true, add `options.setUseProxyProtocol(true)`. Add a new constructor overload or modify the existing builder:

```java
if (proxyProtocol) {
    options.setUseProxyProtocol(true);
    EcsLogger.info("com.auto1.pantera.server")
        .message("PROXY protocol v2 enabled — real client IP from NLB")
        .eventCategory("configuration")
        .eventAction("server_init")
        .log();
}
```

- [ ] **VertxMain** — when constructing `VertxSliceServer`, pass `settings.proxyProtocol()` or equivalent. Both the port 80 server and port 8086 server should get the flag.

- [ ] Run: `mvn compile -pl vertx-server,pantera-main -am` — expect clean

- [ ] Commit: `feat(server): Proxy Protocol v2 support for AWS NLB (config-gated)`

---

## Task 8: UI Trace Propagation

**Files:**
- Modify: `pantera-ui/src/api/client.ts`

- [ ] Add a `traceparent` header to every outgoing request. Insert BEFORE the existing auth interceptor:

```typescript
// Trace propagation: link UI actions to backend spans.
// If Elastic APM RUM is active, use its traceparent. Otherwise generate one.
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const apm = (window as unknown as Record<string, unknown>).__ELASTIC_APM as
        { getCurrentTransaction?: () => { traceparent?: string } | null } | undefined
    if (apm?.getCurrentTransaction) {
        const tx = apm.getCurrentTransaction()
        if (tx?.traceparent) {
            config.headers.traceparent = tx.traceparent
            return config
        }
    }
    // Fallback: generate a traceparent so backend logs correlate with UI actions
    // even when APM is disabled. Format: 00-{traceId32}-{spanId16}-01
    const hex = (n: number) =>
        Array.from(crypto.getRandomValues(new Uint8Array(n)))
            .map(b => b.toString(16).padStart(2, '0')).join('')
    config.headers.traceparent = `00-${hex(16)}-${hex(8)}-01`
    return config
})
```

- [ ] Run: `cd pantera-ui && npx vue-tsc --noEmit` — expect clean

- [ ] Commit: `feat(ui): propagate traceparent header from UI to backend`

---

## Task 9: Inject Trace Headers into Upstream Calls

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/auth/AuthHandler.java` (SSO callback HTTP client calls)
- Modify: `pantera-main/src/main/java/com/auto1/pantera/auth/AuthFromKeycloak.java` (Keycloak token exchange)
- Modify: `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/ProxySlice.java` (upstream fetch)
- Modify: other proxy adapter slices as needed

- [ ] For each outgoing HTTP call, inject trace headers. Example for ProxySlice upstream fetch:

```java
// In fetchFromUpstreamWithHeaders or similar:
final Headers traced = TraceHeaders.inject(extra);
return slice.response(rewritten, traced, Content.EMPTY);
```

For `AuthHandler.callbackEndpoint` which uses `java.net.http.HttpRequest.Builder`:
```java
final String[] traceHdrs = TraceHeaders.httpClientHeaders();
for (int i = 0; i < traceHdrs.length; i += 2) {
    requestBuilder.header(traceHdrs[i], traceHdrs[i + 1]);
}
```

- [ ] Run: `mvn compile -pl pantera-main,pypi-adapter -am` — expect clean

- [ ] Commit: `feat(trace): inject B3/W3C headers into all upstream calls`

---

## Task 10: Startup + Background Task Trace Context

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/VertxMain.java`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/scheduling/QuartzService.java` (if exists)

- [ ] **VertxMain.start()** — set trace context before initialization:

```java
// At the very top of start():
MDC.put(EcsMdc.TRACE_ID, SpanContext.generateId());
MDC.put(EcsMdc.SPAN_ID, SpanContext.generateId());
EcsLogger.info("com.auto1.pantera")
    .message("Pantera starting")
    .eventCategory("process")
    .eventAction("start")
    .log();
```

- [ ] **QuartzService** — in each job's `execute()`, generate fresh trace context:

```java
MDC.put(EcsMdc.TRACE_ID, SpanContext.generateId());
MDC.put(EcsMdc.SPAN_ID, SpanContext.generateId());
try {
    // ... job logic ...
} finally {
    MDC.remove(EcsMdc.TRACE_ID);
    MDC.remove(EcsMdc.SPAN_ID);
}
```

- [ ] Commit: `feat(trace): startup and background job trace context`

---

## Task 11: url.original Verification

**Files:**
- Modify: `pantera-core/src/test/java/com/auto1/pantera/http/log/EcsSchemaValidationTest.java`

- [ ] Add a test asserting `url.original` includes path + query:

```java
@Test
void urlOriginalIncludesPathAndQuery() {
    final EcsLogEvent event = new EcsLogEvent()
        .urlOriginal("/api/v1/artifacts/maven/com/example/lib/1.0/lib-1.0.jar?download=true");
    // Verify the field is set and includes the query string
    // (exact assertion depends on how EcsLogEvent exposes fields)
}
```

- [ ] Verify both ports (80, 8086) use `EcsLoggingSlice` wrapping by checking `VertxSliceServer` and `AsyncApiVerticle`.

- [ ] Commit: `test(ecs): verify url.original includes full path + query`

---

## Task 12: Full Integration Test + Run

**Files:**
- Run full test suites

- [ ] Run: `mvn test -pl pantera-core,pantera-main -am -Dexclude="**/LocateHitRateTest.java"` — all tests pass

- [ ] Run: `cd pantera-ui && npx vue-tsc --noEmit && npm test` — all UI tests pass

- [ ] Commit all files, push to v2.1.0, update PR description with observability section.

---

## Rollout Order (within the branch)

Tasks are ordered for minimal rework:
1. **Tasks 1-3**: Tracing foundation (SpanContext, TraceHeaders, EcsLoggingSlice wiring)
2. **Task 4-5**: Audit logging (AuditLogger, slice integration)
3. **Task 6**: Auth log levels
4. **Task 7**: Proxy Protocol
5. **Task 8-10**: UI trace propagation + upstream injection + startup context
6. **Task 11-12**: Verification + full test run
