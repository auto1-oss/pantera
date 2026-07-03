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
package com.auto1.pantera.http.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.message.MapMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ECS (Elastic Common Schema) compliant logger for non-HTTP application logs.
 *
 * <p>Provides structured logging with proper ECS field mapping and automatic
 * trace.id propagation from MDC. Use this instead of plain Logger calls to ensure
 * all logs are ECS-compliant and contain trace context.
 *
 * <p>Architecture: event-specific fields are emitted in a Log4j2 {@link MapMessage}
 * so their native types (Long, Integer, List) are preserved in the JSON output by
 * {@link co.elastic.logging.log4j2.EcsLayout}. MDC-owned fields (trace.id, client.ip,
 * user.name, etc.) are set by {@link EcsLoggingSlice} and must NOT be set here —
 * that would create duplicate keys in Elasticsearch and cause document rejection.
 *
 * <p>Usage examples:
 * <pre>{@code
 * // Simple message
 * EcsLogger.info("com.auto1.pantera.maven")
 *     .message("Metadata rebuild queued")
 *     .field("package.group", "com.example")
 *     .field("package.name", "my-artifact")
 *     .log();
 *
 * // With error
 * EcsLogger.error("com.auto1.pantera.npm")
 *     .message("Package processing failed")
 *     .error(exception)
 *     .field("package.name", packageName)
 *     .log();
 *
 * // With event metadata
 * EcsLogger.warn("com.auto1.pantera.docker")
 *     .message("Slow cache operation")
 *     .eventCategory("file")
 *     .eventAction("cache_read")
 *     .duration(durationMs)
 *     .log();
 * }</pre>
 *
 * @see <a href="https://www.elastic.co/docs/reference/ecs">ECS Reference</a>
 * @since 1.18.24
 */
public final class EcsLogger {

    private final org.apache.logging.log4j.Logger logger;
    private final LogLevel level;
    private String message;
    private final Map<String, Object> fields = new HashMap<>();

    /**
     * Log levels matching ECS and user requirements.
     */
    public enum LogLevel {
        /** Code tracing - very detailed execution flow */
        TRACE,
        /** Diagnostic information for debugging */
        DEBUG,
        /** Production default - important business events */
        INFO,
        /** Recoverable issues that don't prevent operation */
        WARN,
        /** Operation failures that need attention */
        ERROR,
        /** Catastrophic errors requiring immediate action */
        FATAL
    }

    /**
     * Private constructor - use static factory methods.
     * @param loggerName Logger name (usually class or package name)
     * @param level Log level
     */
    private EcsLogger(final String loggerName, final LogLevel level) {
        this.logger = LogManager.getLogger(loggerName);
        this.level = level;
    }

    /**
     * Create TRACE level logger.
     * @param loggerName Logger name
     * @return Logger builder
     */
    public static EcsLogger trace(final String loggerName) {
        return new EcsLogger(loggerName, LogLevel.TRACE);
    }

    /**
     * Create DEBUG level logger.
     * @param loggerName Logger name
     * @return Logger builder
     */
    public static EcsLogger debug(final String loggerName) {
        return new EcsLogger(loggerName, LogLevel.DEBUG);
    }

    /**
     * Create INFO level logger.
     * @param loggerName Logger name
     * @return Logger builder
     */
    public static EcsLogger info(final String loggerName) {
        return new EcsLogger(loggerName, LogLevel.INFO);
    }

    /**
     * Create WARN level logger.
     * @param loggerName Logger name
     * @return Logger builder
     */
    public static EcsLogger warn(final String loggerName) {
        return new EcsLogger(loggerName, LogLevel.WARN);
    }

    /**
     * Create ERROR level logger.
     * @param loggerName Logger name
     * @return Logger builder
     */
    public static EcsLogger error(final String loggerName) {
        return new EcsLogger(loggerName, LogLevel.ERROR);
    }

    /**
     * Create FATAL level logger (logged as ERROR with fatal marker).
     * @param loggerName Logger name
     * @return Logger builder
     */
    public static EcsLogger fatal(final String loggerName) {
        return new EcsLogger(loggerName, LogLevel.FATAL);
    }

    /**
     * Set log message.
     * @param msg Message
     * @return this
     */
    public EcsLogger message(final String msg) {
        this.message = msg;
        return this;
    }

    /**
     * Add error details (ECS-compliant).
     * @param error Error/Exception
     * @return this
     */
    public EcsLogger error(final Throwable error) {
        this.fields.put("error.message", error.getMessage() != null ? error.getMessage() : error.toString());
        this.fields.put("error.type", error.getClass().getName());
        this.fields.put("error.stack_trace", getStackTrace(error));
        this.fields.put("event.outcome", "failure");
        return this;
    }

    /**
     * Set event category (e.g., "storage", "authentication", "database").
     * ECS requires keyword[] — value is wrapped in a single-element list automatically.
     * @param category Event category
     * @return this
     */
    public EcsLogger eventCategory(final String category) {
        this.fields.put("event.category", List.of(category));
        return this;
    }

    /**
     * Set event action (e.g., "cache_read", "metadata_rebuild", "user_login").
     * @param action Event action
     * @return this
     */
    public EcsLogger eventAction(final String action) {
        this.fields.put("event.action", action);
        return this;
    }

    /**
     * Set event outcome (success, failure, unknown).
     * @param outcome Outcome
     * @return this
     */
    public EcsLogger eventOutcome(final String outcome) {
        this.fields.put("event.outcome", outcome);
        return this;
    }

    /**
     * Set operation duration in milliseconds.
     * @param durationMs Duration
     * @return this
     */
    public EcsLogger duration(final long durationMs) {
        this.fields.put("event.duration", durationMs);
        return this;
    }

    /**
     * Add custom field using ECS dot notation for nested fields.
     * Use dot notation for nested fields (e.g., "maven.group_id", "npm.package_name").
     *
     * <p>Keys listed in {@link EcsMdc#MDC_OWNED_KEYS} (e.g. trace.id, client.ip,
     * user.name, repository.*, package.*) are allowed here but follow a
     * "MDC wins" rule: at {@link #log()} time, if the same key is present in
     * {@link ThreadContext}, the field() value is dropped to avoid a duplicate
     * top-level field in the Elasticsearch document. If MDC does not have the key
     * (for example in async continuations past the HTTP request frame or in CLI
     * tools), the field() value is kept and written to the JSON output.
     *
     * @param key Field key
     * @param value Field value
     * @return this
     */
    public EcsLogger field(final String key, final Object value) {
        if (value != null) {
            this.fields.put(key, value);
        }
        return this;
    }

    /**
     * Log the event at the configured level.
     *
     * <p>Uses Log4j2 {@link MapMessage} to emit typed field values as a structured
     * payload. {@link co.elastic.logging.log4j2.EcsLayout} 1.6+ serializes
     * {@code MapMessage} values with correct JSON types (numbers as numbers, lists
     * as arrays). The {@code message} field inside the payload becomes the
     * top-level ECS {@code message} string.
     *
     * <p>NOTE: MDC-owned keys (trace.id, span.id, client.ip, user.name,
     * repository.type, repository.name, package.name, package.version) are set
     * by {@link EcsLoggingSlice} in ThreadContext and emitted by EcsLayout from
     * there. When such a key is already present in ThreadContext, any value the
     * caller supplied via {@link #field(String, Object)} is dropped from the
     * MapMessage payload to prevent Elasticsearch from rejecting the document with
     * a duplicate top-level field. When ThreadContext does not have that key
     * (async continuations, CLI tools), the field() value is preserved.
     */
    public void log() {
        final String logMessage = this.message != null ? this.message : "Application event";

        // Early out if the level is disabled — avoid building the payload at all.
        if (!isLevelEnabled()) {
            return;
        }

        // Build payload with typed values preserved. For MDC-owned keys, drop them
        // from the payload ONLY when the same key is already populated in ThreadContext —
        // that is the condition that would cause Elasticsearch to reject the document
        // with a duplicate top-level field. When MDC is empty for that key (e.g. async
        // contexts after the request frame, or CLI tools), the field() value is kept so
        // the value still reaches the JSON output.
        final Map<String, Object> payload = new HashMap<>(this.fields.size() + 1);
        for (final Map.Entry<String, Object> entry : this.fields.entrySet()) {
            final String key = entry.getKey();
            if (EcsMdc.isMdcKey(key) && ThreadContext.containsKey(key)) {
                continue;
            }
            payload.put(key, entry.getValue());
        }
        // FATAL degrades to ERROR with event.outcome=failure (ECS 8.x removed FATAL level).
        if (this.level == LogLevel.FATAL) {
            payload.put("event.outcome", "failure");
        }
        // The ECS top-level "message" string is produced from this entry by EcsLayout.
        payload.put("message", logMessage);

        final Throwable error = extractError();
        final MapMessage<?, Object> mapMessage = new MapMessage<>(payload);

        switch (this.level) {
            case TRACE:
                if (error != null) {
                    this.logger.trace(mapMessage, error);
                } else {
                    this.logger.trace(mapMessage);
                }
                break;
            case DEBUG:
                if (error != null) {
                    this.logger.debug(mapMessage, error);
                } else {
                    this.logger.debug(mapMessage);
                }
                break;
            case INFO:
                if (error != null) {
                    this.logger.info(mapMessage, error);
                } else {
                    this.logger.info(mapMessage);
                }
                break;
            case WARN:
                if (error != null) {
                    this.logger.warn(mapMessage, error);
                } else {
                    this.logger.warn(mapMessage);
                }
                break;
            case ERROR:
            case FATAL:
                if (error != null) {
                    this.logger.error(mapMessage, error);
                } else {
                    this.logger.error(mapMessage);
                }
                break;
            default:
                this.logger.info(mapMessage);
                break;
        }
    }

    /**
     * Check whether the configured level is enabled on the underlying logger.
     * Skips payload allocation when the event would be discarded.
     */
    private boolean isLevelEnabled() {
        switch (this.level) {
            case TRACE: return this.logger.isTraceEnabled();
            case DEBUG: return this.logger.isDebugEnabled();
            case INFO:  return this.logger.isInfoEnabled();
            case WARN:  return this.logger.isWarnEnabled();
            case ERROR:
            case FATAL: return this.logger.isErrorEnabled();
            default:    return true;
        }
    }

    /**
     * Extract the Throwable from the fields map if one was attached via
     * {@link #error(Throwable)}. The Throwable is passed separately to the Log4j2
     * logger so the ECS layout can serialize it into {@code error.stack_trace}
     * (and related fields) correctly.
     *
     * <p>The Throwable is kept in the fields map (not only as an instance var)
     * to preserve the existing public contract of {@link #error(Throwable)}
     * which populates {@code error.message}, {@code error.type},
     * {@code error.stack_trace}, {@code event.outcome} fields directly.
     * This extractor is purely for wiring the Log4j2 {@code (Message, Throwable)}
     * overload which only affects how the Throwable is emitted by the layout.
     */
    private Throwable extractError() {
        // Currently error() stores error.* as individual fields and does not
        // retain the Throwable reference. Callers that want native Log4j2
        // Throwable handling should extend this class or use field("error.throwable", t).
        final Object maybe = this.fields.get("error.throwable");
        return maybe instanceof Throwable throwable ? throwable : null;
    }

    /**
     * Get stack trace as string.
     */
    private static String getStackTrace(final Throwable error) {
        final java.io.StringWriter sw = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
