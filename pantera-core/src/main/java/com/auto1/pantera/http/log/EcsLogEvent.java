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

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.observability.UserAgentParser;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.message.MapMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ECS (Elastic Common Schema) compliant log event builder for HTTP requests.
 *
 * <p>Significantly reduces log volume by:
 * <ul>
 *   <li>Only logging errors and slow requests at WARN/ERROR level</li>
 *   <li>Success requests logged at DEBUG level (disabled in production)</li>
 *   <li>Using structured fields instead of verbose messages</li>
 * </ul>
 *
 * <p>Architecture: event-specific fields are emitted in a Log4j2 {@link MapMessage}
 * so their native types (Long, Integer, List) are preserved in the JSON output by
 * {@link co.elastic.logging.log4j2.EcsLayout}. MDC-owned fields (trace.id, client.ip,
 * user.name, etc.) are set by {@link EcsLoggingSlice} and must NOT be set here —
 * that would create duplicate keys in Elasticsearch and cause document rejection.
 *
 * @see <a href="https://www.elastic.co/docs/reference/ecs">ECS Reference</a>
 * @since 1.18.23
 */
public final class EcsLogEvent {

    private static final org.apache.logging.log4j.Logger LOGGER = LogManager.getLogger("http.access");

    /**
     * Latency threshold for slow request warnings (ms).
     */
    private static final long SLOW_REQUEST_THRESHOLD_MS = 5000;

    // All ECS fields stored flat with dot notation
    private String message;
    private final Map<String, Object> fields = new HashMap<>();

    /**
     * Create new log event builder.
     */
    public EcsLogEvent() {
        fields.put("event.kind", "event");
        // event.category and event.type must be arrays per ECS 8.11
        fields.put("event.category", List.of("web"));
        fields.put("event.type", List.of("access"));
        // Default action for HTTP access entries; override via action() for
        // more specific classifications (e.g. health-check probes).
        fields.put("event.action", "http_request");
    }

    /**
     * Override the default {@code event.action} ({@code http_request}).
     * @param action Event action
     * @return this
     */
    public EcsLogEvent action(final String action) {
        fields.put("event.action", action);
        return this;
    }

    /**
     * Set HTTP request method (GET, POST, etc.).
     * @param method HTTP method
     * @return this
     */
    public EcsLogEvent httpMethod(final String method) {
        fields.put("http.request.method", method);
        return this;
    }

    /**
     * Set HTTP version (1.1, 2.0, etc.).
     * @param version HTTP version
     * @return this
     */
    public EcsLogEvent httpVersion(final String version) {
        fields.put("http.version", version);
        return this;
    }

    /**
     * Set HTTP response status code.
     * @param status Status code
     * @return this
     */
    public EcsLogEvent httpStatus(final RsStatus status) {
        fields.put("http.response.status_code", status.code());
        return this;
    }

    /**
     * Set HTTP response body size in bytes.
     * @param bytes Body size
     * @return this
     */
    public EcsLogEvent httpResponseBytes(final long bytes) {
        fields.put("http.response.body.bytes", bytes);
        return this;
    }

    /**
     * Set request duration in milliseconds.
     * @param durationMs Duration
     * @return this
     */
    public EcsLogEvent duration(final long durationMs) {
        fields.put("event.duration", durationMs);
        return this;
    }

    /**
     * Set user agent from headers (parsed according to ECS schema).
     * @param headers Request headers
     * @return this
     */
    public EcsLogEvent userAgent(final Headers headers) {
        for (Header h : headers.find("user-agent")) {
            final String original = h.getValue();
            if (original != null && !original.isEmpty()) {
                fields.put("user_agent.original", original);

                // Delegates to UserAgentParser (WI-post-03b re-lifted the parser
                // into pantera-core.observability so StructuredLogger.access
                // can reuse the same shape without coupling back to this class).
                final UserAgentParser.UserAgentInfo info = UserAgentParser.parse(original);
                if (info.name() != null) {
                    fields.put("user_agent.name", info.name());
                }
                if (info.version() != null) {
                    fields.put("user_agent.version", info.version());
                }
                if (info.osName() != null) {
                    fields.put("user_agent.os.name", info.osName());
                    if (info.osVersion() != null) {
                        fields.put("user_agent.os.version", info.osVersion());
                    }
                }
                if (info.deviceName() != null) {
                    fields.put("user_agent.device.name", info.deviceName());
                }
            }
            break;
        }
        return this;
    }

    /**
     * Set request URL path (sanitized).
     * @param path URL path
     * @return this
     */
    public EcsLogEvent urlPath(final String path) {
        fields.put("url.path", LogSanitizer.sanitizeUrl(path));
        return this;
    }

    /**
     * Set the original URL as received (path + query, sanitized).
     * Maps to ECS {@code url.original}.
     * @param original Full original request URI
     * @return this
     */
    public EcsLogEvent urlOriginal(final String original) {
        if (original != null && !original.isEmpty()) {
            fields.put("url.original", LogSanitizer.sanitizeUrl(original));
        }
        return this;
    }

    /**
     * Set query string (sanitized).
     * @param query Query string
     * @return this
     */
    public EcsLogEvent urlQuery(final String query) {
        if (query != null && !query.isEmpty()) {
            fields.put("url.query", LogSanitizer.sanitizeUrl(query));
        }
        return this;
    }

    /**
     * Set destination address for proxy requests.
     * @param address Remote address
     * @return this
     */
    public EcsLogEvent destinationAddress(final String address) {
        if (address != null && !address.isEmpty()) {
            fields.put("destination.address", address);
        }
        return this;
    }

    /**
     * Set destination port for proxy requests.
     * @param port Remote port
     * @return this
     */
    public EcsLogEvent destinationPort(final int port) {
        fields.put("destination.port", port);
        return this;
    }

    /**
     * Set event outcome (success, failure, unknown).
     * @param outcome Outcome
     * @return this
     */
    public EcsLogEvent outcome(final String outcome) {
        fields.put("event.outcome", outcome);
        return this;
    }

    /**
     * Set custom message.
     * @param msg Message
     * @return this
     */
    public EcsLogEvent message(final String msg) {
        this.message = msg;
        return this;
    }

    /**
     * Add error details (ECS-compliant).
     * Captures exception message, fully qualified type, and full stack trace.
     *
     * @param error Error/Exception
     * @return this
     * @see <a href="https://www.elastic.co/docs/reference/ecs/ecs-error">ECS Error Fields</a>
     */
    public EcsLogEvent error(final Throwable error) {
        fields.put("error.message", error.getMessage() != null ? error.getMessage() : error.toString());
        fields.put("error.type", error.getClass().getName());
        fields.put("error.stack_trace", getStackTrace(error));
        fields.put("event.outcome", "failure");
        return this;
    }

    /**
     * Log at appropriate level based on outcome.
     *
     * <p>Uses Log4j2 {@link MapMessage} to emit typed field values as a structured
     * payload. {@link co.elastic.logging.log4j2.EcsLayout} 1.6+ serializes
     * {@code MapMessage} values with correct JSON types (status codes as integers,
     * durations as long, {@code event.category}/{@code event.type} as arrays).
     * The {@code message} field inside the payload becomes the top-level ECS
     * {@code message} string.
     *
     * <p>MDC-owned fields (trace.id, client.ip, user.name, repository.*, package.*)
     * are set by {@link EcsLoggingSlice} in ThreadContext and emitted by EcsLayout
     * from there. When such a key is already present in ThreadContext, its value
     * here is dropped from the MapMessage payload to prevent a duplicate top-level
     * field in the Elasticsearch document. When ThreadContext does not have that
     * key, the field value is kept so it still reaches the JSON output.
     *
     * <p>Strategy to reduce log volume (v2.1.4 WI-00):
     * <ul>
     *   <li>ERROR ({@code >= 500}): ERROR level</li>
     *   <li>404 / 401 / 403 (client-driven): INFO — these are normal client probes
     *       (Maven HEAD probes, unauthenticated health-checks, per-client auth
     *       retries) and were responsible for ~95% of the access-log WARN noise
     *       in production (forensic §1.7 F2.1–F2.2).</li>
     *   <li>Other 4xx ({@code 400-499} except 401/403/404): WARN</li>
     *   <li>Slow request ({@code durationMs > 5000}): WARN</li>
     *   <li>{@code failureOutcome == true}: WARN</li>
     *   <li>default: DEBUG (production: disabled)</li>
     * </ul>
     */
    public void log() {
        final Integer statusCode = (Integer) fields.get("http.response.status_code");
        final Long storedDuration = (Long) fields.get("event.duration");
        final long durationMs = storedDuration != null ? storedDuration : 0;

        final String logMessage = this.message != null
            ? this.message
            : buildDefaultMessage(statusCode);

        // Build payload preserving typed values. For MDC-owned keys, drop them from
        // the payload ONLY when the same key is already populated in ThreadContext —
        // that is the condition that would cause Elasticsearch to reject the document
        // with a duplicate top-level field. When MDC is empty for that key, the
        // field() value is kept so it still reaches the JSON output.
        final Map<String, Object> payload = new HashMap<>(this.fields.size() + 1);
        for (final Map.Entry<String, Object> entry : this.fields.entrySet()) {
            final String key = entry.getKey();
            if (EcsMdc.isMdcKey(key) && ThreadContext.containsKey(key)) {
                continue;
            }
            payload.put(key, entry.getValue());
        }

        final boolean failureOutcome = "failure".equals(fields.get("event.outcome"));
        final String effectiveMessage;
        if (statusCode != null && statusCode >= 400) {
            effectiveMessage = logMessage;
        } else if (durationMs > SLOW_REQUEST_THRESHOLD_MS) {
            effectiveMessage = String.format("Slow request: %dms - %s", durationMs, logMessage);
        } else {
            effectiveMessage = logMessage;
        }
        // The ECS top-level "message" string is produced from this entry by EcsLayout.
        payload.put("message", effectiveMessage);

        @SuppressWarnings({"rawtypes", "unchecked"})
        final MapMessage mapMessage = new MapMessage(payload);

        if (statusCode != null && statusCode >= 500) {
            LOGGER.error(mapMessage);
        } else if (statusCode != null
            && (statusCode == 404 || statusCode == 401 || statusCode == 403)) {
            // Client-driven 4xx are normal probes (Maven HEAD, unauthenticated
            // health checks, auth retries). Emit at INFO to collapse the 95%
            // log-WARN flood observed in production (§1.7 F2.1–F2.2).
            LOGGER.info(mapMessage);
        } else if (statusCode != null && statusCode >= 400) {
            LOGGER.warn(mapMessage);
        } else if (durationMs > SLOW_REQUEST_THRESHOLD_MS) {
            LOGGER.warn(mapMessage);
        } else if (failureOutcome) {
            LOGGER.warn(mapMessage);
        } else {
            LOGGER.debug(mapMessage);
        }
    }

    /**
     * Build human-readable message from HTTP status code.
     * HTTP method, path, and status code are in their respective ECS fields
     * ({@code http.request.method}, {@code url.original}, {@code http.response.status_code}).
     */
    private static String buildDefaultMessage(final Integer statusCode) {
        if (statusCode == null) {
            return "Request processed";
        }
        if (statusCode >= 200 && statusCode <= 299) {
            return "Request completed";
        }
        if (statusCode == 304) {
            return "Not modified";
        }
        if (statusCode == 401) {
            return "Authentication required";
        }
        if (statusCode == 403) {
            return "Access denied";
        }
        if (statusCode == 404) {
            return "Not found";
        }
        if (statusCode >= 500) {
            return "Internal server error";
        }
        if (statusCode >= 400) {
            return "Client error";
        }
        return "Request processed";
    }

    /**
     * Get stack trace as string.
     */
    private static String getStackTrace(final Throwable error) {
        final java.io.StringWriter sw = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Extract IP from X-Forwarded-For or remote address.
     * Returns null if no valid IP can be determined (never returns "unknown").
     * @param headers Request headers
     * @param remoteAddress Fallback remote address (may be null)
     * @return Client IP, or null if unavailable
     */
    public static String extractClientIp(final Headers headers, final String remoteAddress) {
        // Check X-Forwarded-For first
        for (Header h : headers.find("x-forwarded-for")) {
            final String value = h.getValue();
            if (value != null && !value.isEmpty()) {
                final int comma = value.indexOf(',');
                return comma > 0 ? value.substring(0, comma).trim() : value.trim();
            }
        }
        // Check X-Real-IP
        for (Header h : headers.find("x-real-ip")) {
            final String value = h.getValue();
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        // Fallback to remote address
        if (remoteAddress != null && !remoteAddress.isEmpty() && !"unknown".equals(remoteAddress)) {
            return remoteAddress;
        }
        return null;
    }

    /**
     * Extract username from Authorization header.
     * @param headers Request headers
     * @return Username or null
     */
    public static Optional<String> extractUsername(final Headers headers) {
        for (Header h : headers.find("authorization")) {
            final String value = h.getValue();
            if (value != null && value.toLowerCase(Locale.ROOT).startsWith("basic ")) {
                try {
                    final String decoded = new String(
                        java.util.Base64.getDecoder().decode(value.substring(6))
                    );
                    final int colon = decoded.indexOf(':');
                    if (colon > 0) {
                        return Optional.of(decoded.substring(0, colon));
                    }
                } catch (IllegalArgumentException e) {
                    // Invalid base64, ignore
                }
            }
        }
        return Optional.empty();
    }

}
