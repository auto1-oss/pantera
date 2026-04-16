/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.http.observability;

import com.auto1.pantera.audit.AuditAction;
import com.auto1.pantera.http.context.RequestContext;
import com.auto1.pantera.http.fault.Fault;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.message.MapMessage;

/**
 * Facade for the five-tier observability model — §4.1 / §4.3 of
 * {@code docs/analysis/v2.2-target-architecture.md}.
 *
 * <p>Each tier exposes a builder that requires its tier-specific fields via
 * {@link Objects#requireNonNull(Object, String)} at entry. Java does not have
 * phantom types; enforcing "required field" at the entry point with a clear
 * NPE message is the idiomatic equivalent and gives the same outcome: a
 * request path that forgets to pass {@link RequestContext} fails fast at the
 * first frame rather than silently emitting a log line with a null
 * {@code trace.id}.
 *
 * <p>Each builder binds the current {@link RequestContext} into Log4j2
 * {@link ThreadContext} for the duration of its terminal emission so that
 * {@code EcsLayout} picks up {@code trace.id}, {@code client.ip},
 * {@code user.name}, and the other ECS-owned keys automatically. The prior
 * ThreadContext is restored when emission returns.
 *
 * <p>The five tiers — see {@link LevelPolicy} for the level mapping — are:
 * <ol>
 *   <li>{@link AccessLogger} — Tier-1, client → Pantera (access log).</li>
 *   <li>{@link InternalLogger} — Tier-2, Pantera → Pantera (500 only).</li>
 *   <li>{@link UpstreamLogger} — Tier-3, Pantera → upstream remote.</li>
 *   <li>{@link LocalLogger} — Tier-4, local operations (DB, cache, pool init).</li>
 *   <li>{@link AuditLogger} — Tier-5, compliance audit (non-suppressible INFO).</li>
 * </ol>
 *
 * <p>Callers use the static accessor functions:
 * <pre>{@code
 *   StructuredLogger.access().forRequest(ctx).status(503).fault(fault).log();
 *   StructuredLogger.internal().forCall(ctx, "npm_proxy").fault(fault).error();
 *   StructuredLogger.upstream().forUpstream(ctx, "registry.npmjs.org", 443)
 *     .responseStatus(502).duration(1250L).cause(ex).error();
 *   StructuredLogger.local().forComponent("com.auto1.pantera.index")
 *     .message("executor queue saturated — caller-runs applied").warn();
 *   StructuredLogger.audit().forEvent(ctx, AuditAction.ARTIFACT_PUBLISH)
 *     .packageName("org.springframework:spring-core").packageVersion("6.1.10").emit();
 * }</pre>
 *
 * @since 2.2.0
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.GodClass"})
public final class StructuredLogger {

    private static final String LOGGER_ACCESS = "http.access";
    private static final String LOGGER_INTERNAL = "http.internal";
    private static final String LOGGER_UPSTREAM = "http.upstream";
    private static final String LOGGER_AUDIT = "com.auto1.pantera.audit";

    private static final long SLOW_THRESHOLD_MS = 5000L;

    private static final AccessLogger ACCESS = new AccessLogger();
    private static final InternalLogger INTERNAL = new InternalLogger();
    private static final UpstreamLogger UPSTREAM = new UpstreamLogger();
    private static final LocalLogger LOCAL = new LocalLogger();
    private static final AuditLogger AUDIT = new AuditLogger();

    private StructuredLogger() {
        // facade — not instantiable
    }

    /**
     * @return the shared {@link AccessLogger} (Tier-1).
     */
    public static AccessLogger access() {
        return ACCESS;
    }

    /**
     * @return the shared {@link InternalLogger} (Tier-2).
     */
    public static InternalLogger internal() {
        return INTERNAL;
    }

    /**
     * @return the shared {@link UpstreamLogger} (Tier-3).
     */
    public static UpstreamLogger upstream() {
        return UPSTREAM;
    }

    /**
     * @return the shared {@link LocalLogger} (Tier-4).
     */
    public static LocalLogger local() {
        return LOCAL;
    }

    /**
     * @return the shared {@link AuditLogger} (Tier-5, non-suppressible).
     */
    public static AuditLogger audit() {
        return AUDIT;
    }

    // ======================================================================
    // Tier-1 — AccessLogger (client → Pantera)
    // ======================================================================

    /** Tier-1 factory. Emits one access log line per request. */
    public static final class AccessLogger {

        private static final Logger LOG = LogManager.getLogger(LOGGER_ACCESS);

        private AccessLogger() {
        }

        /**
         * Begin building an access-log record for the given request.
         * @param ctx non-null {@link RequestContext} — refuses {@code null} to
         *            enforce the §4.3 required-field contract.
         * @return a chainable {@link AccessAt} builder.
         * @throws NullPointerException if {@code ctx} is null.
         */
        public AccessAt forRequest(final RequestContext ctx) {
            Objects.requireNonNull(ctx, "ctx");
            return new AccessAt(ctx);
        }
    }

    /**
     * Tier-1 terminal builder. {@link #log()} infers the level from the
     * response status and slow-threshold per {@link LevelPolicy}.
     */
    public static final class AccessAt {

        private final RequestContext ctx;
        private Integer status;
        private String body;
        private Fault fault;
        private Long durationMs;

        private AccessAt(final RequestContext rctx) {
            this.ctx = rctx;
        }

        /** Set {@code http.response.status_code}. */
        public AccessAt status(final int code) {
            this.status = code;
            return this;
        }

        /** Human-readable response body snippet (truncated elsewhere). */
        public AccessAt body(final String bodyText) {
            this.body = bodyText;
            return this;
        }

        /**
         * Attach a {@link Fault}. When the fault is one of the internal /
         * storage / integrity variants, {@code error.type}, {@code error.message},
         * and {@code error.stack_trace} are added to the log payload.
         * @param rcause the Fault (may be null).
         */
        public AccessAt fault(final Fault rcause) {
            this.fault = rcause;
            return this;
        }

        /** Request duration in milliseconds. */
        public AccessAt duration(final long millis) {
            this.durationMs = millis;
            return this;
        }

        /**
         * Emit the access-log line at the level inferred from status +
         * slow-threshold. Never throws.
         */
        public void log() {
            final LevelPolicy policy = choosePolicy();
            final Map<String, Object> payload = buildPayload();
            try (AutoCloseable bound = this.ctx.bindToMdc()) {
                dispatch(AccessLogger.LOG, policy.level(), payload, faultCause(this.fault));
            } catch (final Exception ex) {
                // bindToMdc().close() is declared to throw Exception but our
                // impl never does. This catch is purely for the API contract.
            }
        }

        private LevelPolicy choosePolicy() {
            final int code = this.status == null ? 200 : this.status;
            if (code >= 500) {
                return LevelPolicy.CLIENT_FACING_5XX;
            }
            if (code == 404) {
                return LevelPolicy.CLIENT_FACING_NOT_FOUND;
            }
            if (code == 401 || code == 403) {
                return LevelPolicy.CLIENT_FACING_UNAUTH;
            }
            if (code >= 400) {
                return LevelPolicy.CLIENT_FACING_4XX_OTHER;
            }
            if (this.durationMs != null && this.durationMs > SLOW_THRESHOLD_MS) {
                return LevelPolicy.CLIENT_FACING_SLOW;
            }
            return LevelPolicy.CLIENT_FACING_SUCCESS;
        }

        private Map<String, Object> buildPayload() {
            final Map<String, Object> payload = new HashMap<>();
            payload.put("event.kind", "event");
            payload.put("event.category", List.of("web"));
            payload.put("event.type", List.of("access"));
            payload.put("event.action", "http_request");
            if (this.status != null) {
                payload.put("http.response.status_code", this.status);
            }
            if (this.durationMs != null) {
                payload.put("event.duration", this.durationMs);
            }
            if (this.body != null && !this.body.isEmpty()) {
                payload.put("message", this.body);
            } else {
                payload.put("message", defaultMessage(this.status));
            }
            attachFault(payload, this.fault);
            return payload;
        }
    }

    // ======================================================================
    // Tier-2 — InternalLogger (pantera → pantera, 500-only)
    // ======================================================================

    /** Tier-2 factory. Emits when an internal callee escalates to 500. */
    public static final class InternalLogger {

        private static final Logger LOG = LogManager.getLogger(LOGGER_INTERNAL);

        private InternalLogger() {
        }

        /**
         * Begin an internal-call log record.
         * @param ctx non-null {@link RequestContext}.
         * @param memberName non-null name of the internal callee (e.g. member repo).
         */
        public InternalAt forCall(final RequestContext ctx, final String memberName) {
            Objects.requireNonNull(ctx, "ctx");
            Objects.requireNonNull(memberName, "memberName");
            return new InternalAt(ctx, memberName);
        }
    }

    /** Tier-2 terminal builder. {@link #error()} requires a {@link Fault}. */
    public static final class InternalAt {

        private final RequestContext ctx;
        private final String member;
        private Fault fault;

        private InternalAt(final RequestContext rctx, final String rmember) {
            this.ctx = rctx;
            this.member = rmember;
        }

        /**
         * Attach the 500-triggering fault. Required before {@link #error()}.
         * @param rcause the non-null {@link Fault}.
         */
        public InternalAt fault(final Fault rcause) {
            Objects.requireNonNull(rcause, "fault");
            this.fault = rcause;
            return this;
        }

        /**
         * Emit at {@link LevelPolicy#INTERNAL_CALL_500} (ERROR).
         * @throws IllegalStateException if no {@link Fault} was set.
         */
        public void error() {
            if (this.fault == null) {
                throw new IllegalStateException(
                    "InternalAt.error() requires a Fault; call .fault(...) first"
                );
            }
            emit(LevelPolicy.INTERNAL_CALL_500);
        }

        /** Debug hook for successful internal calls (opt-in tracing). */
        public void debug() {
            emit(LevelPolicy.INTERNAL_CALL_SUCCESS);
        }

        private void emit(final LevelPolicy policy) {
            final Map<String, Object> payload = new HashMap<>();
            payload.put("event.kind", "event");
            payload.put("event.category", List.of("network"));
            payload.put("event.action", "internal_call");
            payload.put("internal.source", nullToEmpty(this.ctx.repoName()));
            payload.put("internal.target", this.member);
            if (this.fault != null) {
                payload.put("message", "Internal call failed: " + this.member);
                attachFault(payload, this.fault);
            } else {
                payload.put("message", "Internal call succeeded: " + this.member);
                payload.put("event.outcome", "success");
            }
            try (AutoCloseable bound = this.ctx.bindToMdc()) {
                dispatch(InternalLogger.LOG, policy.level(), payload, faultCause(this.fault));
            } catch (final Exception ex) {
                // close() never throws in our impl.
            }
        }
    }

    // ======================================================================
    // Tier-3 — UpstreamLogger (pantera → upstream remote)
    // ======================================================================

    /** Tier-3 factory. Emits when an upstream call fails. */
    public static final class UpstreamLogger {

        private static final Logger LOG = LogManager.getLogger(LOGGER_UPSTREAM);

        private UpstreamLogger() {
        }

        /**
         * Begin an upstream-call record.
         * @param ctx non-null {@link RequestContext}.
         * @param destinationAddress non-null remote host.
         * @param destinationPort remote port.
         */
        public UpstreamAt forUpstream(
            final RequestContext ctx,
            final String destinationAddress,
            final int destinationPort
        ) {
            Objects.requireNonNull(ctx, "ctx");
            Objects.requireNonNull(destinationAddress, "destinationAddress");
            return new UpstreamAt(ctx, destinationAddress, destinationPort);
        }
    }

    /** Tier-3 terminal builder. {@link #error()} requires a cause. */
    public static final class UpstreamAt {

        private final RequestContext ctx;
        private final String address;
        private final int port;
        private Integer responseStatus;
        private Long durationMs;
        private Throwable cause;

        private UpstreamAt(final RequestContext rctx, final String raddress, final int rport) {
            this.ctx = rctx;
            this.address = raddress;
            this.port = rport;
        }

        /** Upstream response status code (may be set before .cause() is known). */
        public UpstreamAt responseStatus(final int code) {
            this.responseStatus = code;
            return this;
        }

        /** Upstream call duration in milliseconds. */
        public UpstreamAt duration(final long millis) {
            this.durationMs = millis;
            return this;
        }

        /** Required before {@link #error()}. */
        public UpstreamAt cause(final Throwable throwable) {
            Objects.requireNonNull(throwable, "cause");
            this.cause = throwable;
            return this;
        }

        /**
         * Emit at {@link LevelPolicy#UPSTREAM_5XX}.
         * @throws IllegalStateException if no cause was set.
         */
        public void error() {
            if (this.cause == null) {
                throw new IllegalStateException(
                    "UpstreamAt.error() requires a cause; call .cause(...) first"
                );
            }
            emit(LevelPolicy.UPSTREAM_5XX);
        }

        /** Debug hook for successful upstream calls (opt-in tracing). */
        public void debug() {
            final LevelPolicy policy;
            if (this.responseStatus != null && this.responseStatus == 404) {
                policy = LevelPolicy.UPSTREAM_NOT_FOUND;
            } else {
                policy = LevelPolicy.UPSTREAM_SUCCESS;
            }
            emit(policy);
        }

        private void emit(final LevelPolicy policy) {
            final Map<String, Object> payload = new HashMap<>();
            payload.put("event.kind", "event");
            payload.put("event.category", List.of("network"));
            payload.put("event.action", "upstream_call");
            payload.put("destination.address", this.address);
            payload.put("destination.port", this.port);
            if (this.responseStatus != null) {
                payload.put("http.response.status_code", this.responseStatus);
            }
            if (this.durationMs != null) {
                payload.put("event.duration", this.durationMs);
            }
            if (this.cause != null) {
                payload.put("message", "Upstream call failed: " + this.address);
                payload.put("event.outcome", "failure");
                payload.put("error.type", this.cause.getClass().getName());
                payload.put("error.message",
                    this.cause.getMessage() == null ? this.cause.toString() : this.cause.getMessage());
                payload.put("error.stack_trace", stackTraceOf(this.cause));
            } else {
                payload.put("message", "Upstream call: " + this.address);
            }
            try (AutoCloseable bound = this.ctx.bindToMdc()) {
                dispatch(UpstreamLogger.LOG, policy.level(), payload, this.cause);
            } catch (final Exception ex) {
                // close() never throws in our impl.
            }
        }
    }

    // ======================================================================
    // Tier-4 — LocalLogger (local ops)
    // ======================================================================

    /** Tier-4 factory. Caller supplies the component name (=logger name). */
    public static final class LocalLogger {

        private LocalLogger() {
        }

        /**
         * Begin a local-op record.
         * @param component non-null component / logger name
         *                  (e.g. {@code "com.auto1.pantera.index"}).
         */
        public LocalAt forComponent(final String component) {
            Objects.requireNonNull(component, "component");
            return new LocalAt(component);
        }
    }

    /** Tier-4 terminal builder. */
    public static final class LocalAt {

        private final String component;
        private String message;
        private RequestContext reqCtx;
        private Throwable cause;
        private final Map<String, Object> fields = new HashMap<>();

        private LocalAt(final String rcomponent) {
            this.component = rcomponent;
        }

        /** Required before any terminal. */
        public LocalAt message(final String msg) {
            this.message = msg;
            return this;
        }

        /** Optional — attaches trace.id etc. when the op is request-linked. */
        public LocalAt reqCtx(final RequestContext ctx) {
            this.reqCtx = ctx;
            return this;
        }

        /** Add a custom ECS field (dot notation). */
        public LocalAt field(final String key, final Object value) {
            Objects.requireNonNull(key, "key");
            if (value != null) {
                this.fields.put(key, value);
            }
            return this;
        }

        /** Required before {@link #error()}; optional on {@link #warn()}. */
        public LocalAt cause(final Throwable throwable) {
            this.cause = throwable;
            return this;
        }

        /** Config-change / lifecycle event. */
        public void info() {
            emit(LevelPolicy.LOCAL_CONFIG_CHANGE);
        }

        /** Op-success debug hook. */
        public void debug() {
            emit(LevelPolicy.LOCAL_OP_SUCCESS);
        }

        /** Degradation warning (shed, fallback, retry, queue-near-full). */
        public void warn() {
            emit(LevelPolicy.LOCAL_DEGRADED);
        }

        /**
         * Local-op failure.
         * @throws NullPointerException if no cause was set (required).
         */
        public void error() {
            Objects.requireNonNull(
                this.cause,
                "LocalAt.error() requires a cause; call .cause(...) first"
            );
            emit(LevelPolicy.LOCAL_FAILURE);
        }

        private void emit(final LevelPolicy policy) {
            if (this.message == null) {
                throw new IllegalStateException(
                    "LocalAt requires .message(...) before terminal"
                );
            }
            final Map<String, Object> payload = new HashMap<>(this.fields);
            payload.put("message", this.message);
            if (this.cause != null) {
                payload.put("event.outcome", "failure");
                payload.put("error.type", this.cause.getClass().getName());
                payload.put("error.message",
                    this.cause.getMessage() == null ? this.cause.toString() : this.cause.getMessage());
                payload.put("error.stack_trace", stackTraceOf(this.cause));
            }
            final Logger logger = LogManager.getLogger(this.component);
            if (this.reqCtx != null) {
                try (AutoCloseable bound = this.reqCtx.bindToMdc()) {
                    dispatch(logger, policy.level(), payload, this.cause);
                } catch (final Exception ex) {
                    // close() never throws
                }
            } else {
                dispatch(logger, policy.level(), payload, this.cause);
            }
        }
    }

    // ======================================================================
    // Tier-5 — AuditLogger (compliance, non-suppressible)
    // ======================================================================

    /** Tier-5 factory. */
    public static final class AuditLogger {

        private static final Logger LOG = LogManager.getLogger(LOGGER_AUDIT);

        private AuditLogger() {
        }

        /**
         * Begin an audit record.
         * @param ctx non-null {@link RequestContext} (for trace.id / user.name / client.ip).
         * @param action non-null {@link AuditAction}.
         */
        public AuditAt forEvent(final RequestContext ctx, final AuditAction action) {
            Objects.requireNonNull(ctx, "ctx");
            Objects.requireNonNull(action, "action");
            return new AuditAt(ctx, action);
        }
    }

    /**
     * Tier-5 terminal builder. Emits at INFO, non-suppressible, to the audit
     * dataset via {@code event.category=audit} + {@code data_stream.dataset=pantera.audit}.
     */
    public static final class AuditAt {

        private final RequestContext ctx;
        private final AuditAction action;
        private String packageName;
        private String packageVersion;
        private String packageChecksum;
        private String outcome;

        private AuditAt(final RequestContext rctx, final AuditAction raction) {
            this.ctx = rctx;
            this.action = raction;
        }

        /** Required before {@link #emit()}. */
        public AuditAt packageName(final String name) {
            this.packageName = name;
            return this;
        }

        /** Required before {@link #emit()}. */
        public AuditAt packageVersion(final String version) {
            this.packageVersion = version;
            return this;
        }

        /** Optional (known on PUBLISH / DOWNLOAD, unknown on RESOLUTION). */
        public AuditAt packageChecksum(final String sha256Hex) {
            this.packageChecksum = sha256Hex;
            return this;
        }

        /** Optional — {@code success} / {@code failure} / {@code unknown}. */
        public AuditAt outcome(final String outcomeKey) {
            this.outcome = outcomeKey;
            return this;
        }

        /**
         * Emit the audit record at INFO. Always fires, regardless of operational
         * log levels (the audit logger config must not suppress it).
         * @throws NullPointerException on missing required fields.
         */
        public void emit() {
            Objects.requireNonNull(this.packageName, "packageName");
            Objects.requireNonNull(this.packageVersion, "packageVersion");
            final Map<String, Object> payload = new HashMap<>();
            payload.put("message", buildMessage());
            payload.put("event.kind", "event");
            payload.put("event.category", List.of("audit"));
            payload.put("event.action", actionToken(this.action));
            payload.put("data_stream.dataset", "pantera.audit");
            payload.put("package.name", this.packageName);
            payload.put("package.version", this.packageVersion);
            if (this.packageChecksum != null && !this.packageChecksum.isEmpty()) {
                payload.put("package.checksum", this.packageChecksum);
            }
            if (this.outcome != null && !this.outcome.isEmpty()) {
                payload.put("event.outcome", this.outcome);
            } else {
                payload.put("event.outcome", "success");
            }
            try (AutoCloseable bound = this.ctx.bindToMdc()) {
                dispatch(AuditLogger.LOG, LevelPolicy.AUDIT_EVENT.level(), payload, null);
            } catch (final Exception ex) {
                // close() never throws
            }
        }

        private String buildMessage() {
            return "Audit: " + actionToken(this.action) + " "
                + this.packageName + "@" + this.packageVersion;
        }
    }

    // ======================================================================
    // Shared helpers
    // ======================================================================

    /**
     * Dispatch a {@link MapMessage} at the requested {@link Level}. Log4j2's
     * level-specific API preserves {@link co.elastic.logging.log4j2.EcsLayout}'s
     * typed-field rendering, so payload values stay as native JSON types
     * (ints, longs, string arrays) instead of being stringified.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void dispatch(
        final Logger logger,
        final Level level,
        final Map<String, Object> payload,
        final Throwable cause
    ) {
        if (!logger.isEnabled(level)) {
            return;
        }
        // Drop payload keys that are already in ThreadContext / MDC to avoid
        // duplicate top-level fields in the Elasticsearch document.
        final Map<String, Object> filtered = new HashMap<>(payload.size());
        for (final Map.Entry<String, Object> e : payload.entrySet()) {
            if (ThreadContext.containsKey(e.getKey())) {
                continue;
            }
            filtered.put(e.getKey(), e.getValue());
        }
        final MapMessage msg = new MapMessage(filtered);
        if (level == Level.ERROR) {
            if (cause != null) {
                logger.error(msg, cause);
            } else {
                logger.error(msg);
            }
        } else if (level == Level.WARN) {
            if (cause != null) {
                logger.warn(msg, cause);
            } else {
                logger.warn(msg);
            }
        } else if (level == Level.INFO) {
            if (cause != null) {
                logger.info(msg, cause);
            } else {
                logger.info(msg);
            }
        } else if (level == Level.DEBUG) {
            if (cause != null) {
                logger.debug(msg, cause);
            } else {
                logger.debug(msg);
            }
        } else if (level == Level.TRACE) {
            if (cause != null) {
                logger.trace(msg, cause);
            } else {
                logger.trace(msg);
            }
        } else {
            logger.log(level, msg);
        }
    }

    /**
     * Attach {@code error.type} / {@code error.message} / {@code error.stack_trace}
     * for faults that escalate to 5xx. Structural (non-500) faults contribute
     * their enum-like payload without a stack trace.
     */
    private static void attachFault(final Map<String, Object> payload, final Fault rcause) {
        if (rcause == null) {
            return;
        }
        if (rcause instanceof Fault.Internal internal) {
            payload.put("event.outcome", "failure");
            payload.put("error.type", internal.cause().getClass().getName());
            payload.put("error.message", messageOf(internal.cause()));
            payload.put("error.stack_trace", stackTraceOf(internal.cause()));
            payload.put("fault.where", internal.where());
        } else if (rcause instanceof Fault.StorageUnavailable storage) {
            payload.put("event.outcome", "failure");
            payload.put("error.type", storage.cause().getClass().getName());
            payload.put("error.message", messageOf(storage.cause()));
            payload.put("error.stack_trace", stackTraceOf(storage.cause()));
            payload.put("fault.key", storage.key());
        } else if (rcause instanceof Fault.IndexUnavailable index) {
            payload.put("event.outcome", "failure");
            payload.put("error.type", index.cause().getClass().getName());
            payload.put("error.message", messageOf(index.cause()));
            payload.put("error.stack_trace", stackTraceOf(index.cause()));
            payload.put("fault.query", index.query());
        } else if (rcause instanceof Fault.UpstreamIntegrity integrity) {
            payload.put("event.outcome", "failure");
            payload.put("error.type", "UpstreamIntegrity");
            payload.put("error.message",
                "Checksum mismatch: " + integrity.algo()
                    + " claimed=" + integrity.sidecarClaim()
                    + " computed=" + integrity.computed());
            payload.put("fault.upstream_uri", integrity.upstreamUri());
        } else if (rcause instanceof Fault.NotFound notfound) {
            payload.put("fault.scope", nullToEmpty(notfound.scope()));
            payload.put("fault.artifact", nullToEmpty(notfound.artifact()));
        } else if (rcause instanceof Fault.Forbidden forbidden) {
            payload.put("fault.reason", forbidden.reason());
        } else if (rcause instanceof Fault.Deadline deadline) {
            payload.put("event.outcome", "failure");
            payload.put("fault.where", deadline.where());
        } else if (rcause instanceof Fault.Overload overload) {
            payload.put("event.outcome", "failure");
            payload.put("fault.resource", overload.resource());
        } else if (rcause instanceof Fault.AllProxiesFailed all) {
            payload.put("event.outcome", "failure");
            payload.put("fault.group", all.group());
        }
    }

    private static Throwable faultCause(final Fault fault) {
        if (fault instanceof Fault.Internal internal) {
            return internal.cause();
        }
        if (fault instanceof Fault.StorageUnavailable storage) {
            return storage.cause();
        }
        if (fault instanceof Fault.IndexUnavailable index) {
            return index.cause();
        }
        return null;
    }

    private static String defaultMessage(final Integer status) {
        if (status == null) {
            return "Request processed";
        }
        if (status >= 500) {
            return "Internal server error";
        }
        if (status == 404) {
            return "Not found";
        }
        if (status == 401) {
            return "Authentication required";
        }
        if (status == 403) {
            return "Access denied";
        }
        if (status >= 400) {
            return "Client error";
        }
        return "Request completed";
    }

    private static String actionToken(final AuditAction action) {
        return switch (action) {
            case ARTIFACT_PUBLISH -> "artifact_publish";
            case ARTIFACT_DOWNLOAD -> "artifact_download";
            case ARTIFACT_DELETE -> "artifact_delete";
            case RESOLUTION -> "artifact_resolution";
        };
    }

    private static String messageOf(final Throwable t) {
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    private static String stackTraceOf(final Throwable t) {
        final StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }
}
