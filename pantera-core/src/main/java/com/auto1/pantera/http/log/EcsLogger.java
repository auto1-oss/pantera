/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.message.MapMessage;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * ECS (Elastic Common Schema) compliant logger for non-HTTP application logs.
 * 
 * <p>Provides structured logging with proper ECS field mapping and automatic
 * trace.id propagation from MDC. Use this instead of plain Logger calls to ensure
 * all logs are ECS-compliant and contain trace context.
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
 *     .eventCategory("storage")
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
     * @param category Event category
     * @return this
     */
    public EcsLogger eventCategory(final String category) {
        this.fields.put("event.category", category);
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
        this.fields.put("event.duration", durationMs * 1_000_000); // Convert to nanoseconds (ECS standard)
        return this;
    }

    /**
     * Add custom field using ECS dot notation for nested fields.
     * Use dot notation for nested fields (e.g., "maven.group_id", "npm.package_name").
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
     * Add user name (authenticated user).
     * @param username Username
     * @return this
     */
    public EcsLogger userName(final String username) {
        if (username != null && !username.isEmpty()) {
            this.fields.put("user.name", username);
        }
        return this;
    }

    /**
     * Log the event at the configured level.
     * Uses Log4j2 MapMessage for proper structured JSON output with ECS fields.
     */
    public void log() {
        // Add trace context from MDC if available (ECS tracing fields)
        // These are set by EcsLoggingSlice at request start for request correlation
        final String traceId = MDC.get("trace.id");
        if (traceId != null && !traceId.isEmpty()) {
            this.fields.put("trace.id", traceId);
        }
        
        // Add client.ip from MDC if not already set
        if (!this.fields.containsKey("client.ip")) {
            final String clientIp = MDC.get("client.ip");
            if (clientIp != null && !clientIp.isEmpty()) {
                this.fields.put("client.ip", clientIp);
            }
        }
        
        // Add user.name from MDC if not already set
        if (!this.fields.containsKey("user.name")) {
            final String userName = MDC.get("user.name");
            if (userName != null && !userName.isEmpty()) {
                this.fields.put("user.name", userName);
            }
        }

        // Add data stream fields (ECS data_stream.*)
        this.fields.put("data_stream.type", "logs");
        this.fields.put("data_stream.dataset", "pantera.log");

        // Create MapMessage with all fields for structured JSON output
        final MapMessage mapMessage = new MapMessage(this.fields);

        // Set the message text
        final String logMessage = this.message != null ? this.message : "Application event";
        mapMessage.with("message", logMessage);

        // Log at appropriate level using MapMessage for structured output
        switch (this.level) {
            case TRACE:
                if (this.logger.isTraceEnabled()) {
                    this.logger.trace(mapMessage);
                }
                break;
            case DEBUG:
                if (this.logger.isDebugEnabled()) {
                    this.logger.debug(mapMessage);
                }
                break;
            case INFO:
                this.logger.info(mapMessage);
                break;
            case WARN:
                this.logger.warn(mapMessage);
                break;
            case ERROR:
                this.logger.error(mapMessage);
                break;
            case FATAL:
                // FATAL logged as ERROR with special marker
                mapMessage.with("event.severity", "fatal");
                this.logger.error(mapMessage);
                break;
        }
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

