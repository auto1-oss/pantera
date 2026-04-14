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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;

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
 * <p>Architecture: event-specific fields are injected via {@link CloseableThreadContext}
 * for the duration of one logger call. MDC-owned fields (trace.id, client.ip, user.name)
 * are set by {@link EcsLoggingSlice} and must NOT be set here — that would create
 * duplicate keys in Elasticsearch and cause document rejection.
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

                // Parse user agent (basic parsing - can be enhanced with ua-parser library)
                final UserAgentInfo info = parseUserAgent(original);
                if (info.name != null) {
                    fields.put("user_agent.name", info.name);
                }
                if (info.version != null) {
                    fields.put("user_agent.version", info.version);
                }
                if (info.osName != null) {
                    fields.put("user_agent.os.name", info.osName);
                    if (info.osVersion != null) {
                        fields.put("user_agent.os.version", info.osVersion);
                    }
                }
                if (info.deviceName != null) {
                    fields.put("user_agent.device.name", info.deviceName);
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
     * <p>Uses {@link CloseableThreadContext} to inject event-specific fields as MDC entries
     * for the duration of one logger call. This avoids MapMessage's duplicate-message
     * issue while still producing structured ECS JSON output via EcsLayout.
     *
     * <p>MDC-owned fields (trace.id, client.ip, user.name) are set by EcsLoggingSlice and
     * must not appear in this field map — EcsLayout merges both sources and ES rejects
     * documents with duplicate top-level keys.
     *
     * <p>Strategy to reduce log volume:
     * <ul>
     *   <li>ERROR (>= 500): Always log at ERROR level</li>
     *   <li>WARN (>= 400 or slow >5s): Log at WARN level</li>
     *   <li>SUCCESS (< 400): Log at DEBUG level (production: disabled)</li>
     * </ul>
     */
    public void log() {
        final Integer statusCode = (Integer) fields.get("http.response.status_code");
        final Long storedDuration = (Long) fields.get("event.duration");
        final long durationMs = storedDuration != null ? storedDuration : 0;

        final String logMessage = this.message != null
            ? this.message
            : buildDefaultMessage(statusCode);

        // Inject event-specific fields into ThreadContext for this log call.
        // ThreadContext.putAll/remove are used directly (not CloseableThreadContext) to avoid
        // a NPE in CloseableThreadContext.putAll() when ThreadContext map is null in test env.
        final Map<String, String> added = toStringMap(this.fields);
        ThreadContext.putAll(added);
        try {
            final boolean failureOutcome = "failure".equals(fields.get("event.outcome"));
            if (statusCode != null && statusCode >= 500) {
                LOGGER.error(logMessage);
            } else if (statusCode != null && statusCode >= 400) {
                LOGGER.warn(logMessage);
            } else if (durationMs > SLOW_REQUEST_THRESHOLD_MS) {
                LOGGER.warn(String.format("Slow request: %dms - %s", durationMs, logMessage));
            } else if (failureOutcome) {
                LOGGER.warn(logMessage);
            } else {
                LOGGER.debug(logMessage);
            }
        } finally {
            for (final String key : added.keySet()) {
                ThreadContext.remove(key);
            }
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
     * Convert field map to String map for CloseableThreadContext.
     * List values (event.category, event.type) are serialized as JSON arrays
     * so EcsLayout can write them as proper keyword[] arrays in JSON output.
     */
    private static Map<String, String> toStringMap(final Map<String, Object> fields) {
        final Map<String, String> result = new HashMap<>(fields.size());
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof List<?> list) {
                // Serialize as JSON array string — EcsLayout preserves raw JSON strings
                final StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(list.get(i)).append("\"");
                }
                sb.append("]");
                result.put(entry.getKey(), sb.toString());
            } else if (value != null) {
                result.put(entry.getKey(), value.toString());
            }
        }
        return result;
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
            if (value != null && value.toLowerCase().startsWith("basic ")) {
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

    /**
     * Parse user agent string into ECS components.
     */
    private static UserAgentInfo parseUserAgent(final String ua) {
        final UserAgentInfo info = new UserAgentInfo();

        if (ua == null || ua.isEmpty()) {
            return info;
        }

        if (ua.startsWith("Maven/")) {
            info.name = "Maven";
            extractVersion(ua, "Maven/", info);
        } else if (ua.startsWith("npm/")) {
            info.name = "npm";
            extractVersion(ua, "npm/", info);
        } else if (ua.startsWith("pip/")) {
            info.name = "pip";
            extractVersion(ua, "pip/", info);
        } else if (ua.contains("Docker-Client/")) {
            info.name = "Docker";
            extractVersion(ua, "Docker-Client/", info);
        } else if (ua.startsWith("Go-http-client/")) {
            info.name = "Go";
            extractVersion(ua, "Go-http-client/", info);
        } else if (ua.startsWith("Gradle/")) {
            info.name = "Gradle";
            extractVersion(ua, "Gradle/", info);
        } else if (ua.contains("Composer/")) {
            info.name = "Composer";
            extractVersion(ua, "Composer/", info);
        } else if (ua.startsWith("NuGet")) {
            info.name = "NuGet";
            if (ua.contains("/")) {
                extractVersion(ua, "NuGet Command Line/", info);
            }
        } else if (ua.contains("curl/")) {
            info.name = "curl";
            extractVersion(ua, "curl/", info);
        } else if (ua.contains("wget/")) {
            info.name = "wget";
            extractVersion(ua, "wget/", info);
        }

        if (ua.contains("Linux")) {
            info.osName = "Linux";
        } else if (ua.contains("Windows")) {
            info.osName = "Windows";
        } else if (ua.contains("Mac OS X") || ua.contains("Darwin")) {
            info.osName = "macOS";
        } else if (ua.contains("FreeBSD")) {
            info.osName = "FreeBSD";
        }

        if (ua.contains("Java/")) {
            final int start = ua.indexOf("Java/") + 5;
            final int end = findVersionEnd(ua, start);
            if (end > start) {
                info.osVersion = ua.substring(start, end);
            }
        }

        return info;
    }

    private static void extractVersion(final String ua, final String prefix, final UserAgentInfo info) {
        final int start = ua.indexOf(prefix);
        if (start >= 0) {
            final int versionStart = start + prefix.length();
            final int versionEnd = findVersionEnd(ua, versionStart);
            if (versionEnd > versionStart) {
                info.version = ua.substring(versionStart, versionEnd);
            }
        }
    }

    private static int findVersionEnd(final String ua, final int start) {
        int end = start;
        while (end < ua.length()) {
            final char c = ua.charAt(end);
            if (c == ' ' || c == ';' || c == '(' || c == ')') {
                break;
            }
            end++;
        }
        return end;
    }

    private static final class UserAgentInfo {
        String name;
        String version;
        String osName;
        String osVersion;
        String deviceName;
    }
}
