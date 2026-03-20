/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.message.MapMessage;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * ECS-compliant logger for ASTO module.
 * Provides structured logging with Elastic Common Schema field mapping.
 * This is a simplified version for the asto module that doesn't depend on pantera-core.
 *
 * @since 1.19.1
 */
public final class EcsLogger {

    /**
     * Logger name/category.
     */
    private final String category;

    /**
     * Log level.
     */
    private final Level level;

    /**
     * Log message.
     */
    private String message;

    /**
     * Event category (ECS field: event.category).
     */
    private String eventCategory;

    /**
     * Event action (ECS field: event.action).
     */
    private String eventAction;

    /**
     * Event outcome (ECS field: event.outcome).
     */
    private String eventOutcome;

    /**
     * Exception to log.
     */
    private Throwable exception;

    /**
     * Additional structured fields.
     */
    private final Map<String, Object> fields = new HashMap<>();

    /**
     * Private constructor.
     * @param category Logger category
     * @param level Log level
     */
    private EcsLogger(final String category, final Level level) {
        this.category = category;
        this.level = level;
    }

    /**
     * Create TRACE level logger.
     * @param category Logger category
     * @return EcsLogger instance
     */
    public static EcsLogger trace(final String category) {
        return new EcsLogger(category, Level.TRACE);
    }

    /**
     * Create DEBUG level logger.
     * @param category Logger category
     * @return EcsLogger instance
     */
    public static EcsLogger debug(final String category) {
        return new EcsLogger(category, Level.DEBUG);
    }

    /**
     * Create INFO level logger.
     * @param category Logger category
     * @return EcsLogger instance
     */
    public static EcsLogger info(final String category) {
        return new EcsLogger(category, Level.INFO);
    }

    /**
     * Create WARN level logger.
     * @param category Logger category
     * @return EcsLogger instance
     */
    public static EcsLogger warn(final String category) {
        return new EcsLogger(category, Level.WARN);
    }

    /**
     * Create ERROR level logger.
     * @param category Logger category
     * @return EcsLogger instance
     */
    public static EcsLogger error(final String category) {
        return new EcsLogger(category, Level.ERROR);
    }

    /**
     * Set log message.
     * @param msg Message
     * @return This instance for chaining
     */
    public EcsLogger message(final String msg) {
        this.message = msg;
        return this;
    }

    /**
     * Set event category (ECS field: event.category).
     * @param category Event category (e.g., "storage", "cache", "factory")
     * @return This instance for chaining
     */
    public EcsLogger eventCategory(final String category) {
        this.eventCategory = category;
        return this;
    }

    /**
     * Set event action (ECS field: event.action).
     * @param action Event action (e.g., "list_keys", "save", "delete")
     * @return This instance for chaining
     */
    public EcsLogger eventAction(final String action) {
        this.eventAction = action;
        return this;
    }

    /**
     * Set event outcome (ECS field: event.outcome).
     * @param outcome Event outcome ("success", "failure", "unknown")
     * @return This instance for chaining
     */
    public EcsLogger eventOutcome(final String outcome) {
        this.eventOutcome = outcome;
        return this;
    }

    /**
     * Set exception to log.
     * @param throwable Exception
     * @return This instance for chaining
     */
    public EcsLogger error(final Throwable throwable) {
        this.exception = throwable;
        if (throwable != null) {
            this.fields.put("error.type", throwable.getClass().getName());
            this.fields.put("error.message", throwable.getMessage());
        }
        return this;
    }

    /**
     * Add custom field.
     * @param name Field name (use ECS naming: storage.*, file.*, cache.*, etc.)
     * @param value Field value
     * @return This instance for chaining
     */
    public EcsLogger field(final String name, final Object value) {
        if (value != null) {
            this.fields.put(name, value);
        }
        return this;
    }

    /**
     * Emit the log entry using Log4j2 MapMessage for proper structured JSON output.
     */
    public void log() {
        final org.apache.logging.log4j.Logger logger = LogManager.getLogger(this.category);

        // Add trace.id from MDC if available
        final String traceId = MDC.get("trace.id");
        if (traceId != null && !traceId.isEmpty()) {
            this.fields.put("trace.id", traceId);
        }

        // Add data stream fields (ECS data_stream.*)
        this.fields.put("data_stream.type", "logs");
        this.fields.put("data_stream.dataset", "pantera.log");

        // Add event fields
        if (this.eventCategory != null) {
            this.fields.put("event.category", this.eventCategory);
        }
        if (this.eventAction != null) {
            this.fields.put("event.action", this.eventAction);
        }
        if (this.eventOutcome != null) {
            this.fields.put("event.outcome", this.eventOutcome);
        }

        // Create MapMessage with all fields for structured JSON output
        final MapMessage mapMessage = new MapMessage(this.fields);

        // Set the message text
        final String logMessage = this.message != null ? this.message : "Storage event";
        mapMessage.with("message", logMessage);

        // Log at appropriate level using MapMessage for structured output
        switch (this.level) {
            case TRACE:
                if (this.exception != null) {
                    logger.trace(mapMessage, this.exception);
                } else {
                    logger.trace(mapMessage);
                }
                break;
            case DEBUG:
                if (this.exception != null) {
                    logger.debug(mapMessage, this.exception);
                } else {
                    logger.debug(mapMessage);
                }
                break;
            case INFO:
                if (this.exception != null) {
                    logger.info(mapMessage, this.exception);
                } else {
                    logger.info(mapMessage);
                }
                break;
            case WARN:
                if (this.exception != null) {
                    logger.warn(mapMessage, this.exception);
                } else {
                    logger.warn(mapMessage);
                }
                break;
            case ERROR:
                if (this.exception != null) {
                    logger.error(mapMessage, this.exception);
                } else {
                    logger.error(mapMessage);
                }
                break;
            default:
                throw new IllegalStateException("Unknown log level: " + this.level);
        }
    }

    /**
     * Log level enum.
     */
    private enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }
}

