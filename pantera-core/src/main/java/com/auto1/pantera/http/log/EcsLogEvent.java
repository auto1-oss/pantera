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
import org.apache.logging.log4j.message.MapMessage;

import java.util.HashMap;
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
 * @see <a href="https://www.elastic.co/docs/reference/ecs">ECS Reference</a>
 * @since 1.18.23
 */
public final class EcsLogEvent {

    private static final org.apache.logging.log4j.Logger LOGGER = LogManager.getLogger("http.access");
    
    /**
     * Latency threshold for slow request warnings (ms).
     */
    private static final long SLOW_REQUEST_THRESHOLD_MS = 5000;

    // All ECS fields stored flat with dot notation for proper JSON serialization
    private String message;
    private final Map<String, Object> fields = new HashMap<>();

    /**
     * Create new log event builder.
     */
    public EcsLogEvent() {
        fields.put("event.kind", "event");
        fields.put("event.category", "web");
        fields.put("event.type", "access");
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
        fields.put("event.duration", durationMs * 1_000_000); // Convert to nanoseconds (ECS standard)
        return this;
    }

    /**
     * Set client IP address.
     * @param ip Client IP
     * @return this
     */
    public EcsLogEvent clientIp(final String ip) {
        fields.put("client.ip", ip);
        return this;
    }

    /**
     * Set client port.
     * @param port Client port
     * @return this
     */
    public EcsLogEvent clientPort(final int port) {
        fields.put("client.port", port);
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
     * Set authenticated username.
     * @param username Username
     * @return this
     */
    public EcsLogEvent userName(final String username) {
        if (username != null && !username.isEmpty()) {
            fields.put("user.name", username);
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
        // ECS error.message - The error message
        fields.put("error.message", error.getMessage() != null ? error.getMessage() : error.toString());

        // ECS error.type - Fully qualified class name for better categorization
        fields.put("error.type", error.getClass().getName());

        // ECS error.stack_trace - Full stack trace as string
        fields.put("error.stack_trace", getStackTrace(error));

        fields.put("event.outcome", "failure");
        return this;
    }

    /**
     * Log at appropriate level based on outcome.
     *
     * <p>Strategy to reduce log volume:
     * <ul>
     *   <li>ERROR (>= 500): Always log at ERROR level</li>
     *   <li>WARN (>= 400 or slow >5s): Log at WARN level</li>
     *   <li>SUCCESS (< 400): Log at DEBUG level (production: disabled)</li>
     * </ul>
     */
    public void log() {
        // NOTE: trace.id, client.ip, user.name are in MDC (set by EcsLoggingSlice).
        // EcsLayout automatically includes all MDC entries in JSON output.
        // Do NOT copy them into MapMessage — that causes duplicate fields in Elastic.

        // Determine log level based on status and duration
        final Integer statusCode = (Integer) fields.get("http.response.status_code");
        final Long durationNs = (Long) fields.get("event.duration");
        final long durationMs = durationNs != null ? durationNs / 1_000_000 : 0;

        final String logMessage = this.message != null
            ? this.message
            : buildDefaultMessage(statusCode);

        // Create MapMessage with all fields for structured JSON output
        final MapMessage mapMessage = new MapMessage(fields);
        mapMessage.with("message", logMessage);

        final boolean failureOutcome = "failure".equals(fields.get("event.outcome"));
        if (statusCode != null && statusCode >= 500) {
            mapMessage.with("event.severity", "critical");
            LOGGER.info(mapMessage);
        } else if (statusCode != null && statusCode >= 400) {
            mapMessage.with("event.severity", "warning");
            LOGGER.info(mapMessage);
        } else if (durationMs > SLOW_REQUEST_THRESHOLD_MS) {
            mapMessage.with("event.severity", "warning");
            mapMessage.with("message", String.format("Slow request: %dms - %s", durationMs, logMessage));
            LOGGER.info(mapMessage);
        } else if (failureOutcome) {
            LOGGER.info(mapMessage);
        } else {
            LOGGER.debug(mapMessage);
        }
    }

    /**
     * Build default message from ECS fields.
     */
    private String buildDefaultMessage(final Integer statusCode) {
        final String method = (String) fields.get("http.request.method");
        final String path = (String) fields.get("url.path");
        return String.format("%s %s %d",
            method != null ? method : "?",
            path != null ? path : "?",
            statusCode != null ? statusCode : 0
        );
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
     * @param headers Request headers
     * @param remoteAddress Fallback remote address
     * @return Client IP
     */
    public static String extractClientIp(final Headers headers, final String remoteAddress) {
        // Check X-Forwarded-For first
        for (Header h : headers.find("x-forwarded-for")) {
            final String value = h.getValue();
            if (value != null && !value.isEmpty()) {
                // Get first IP in list (original client)
                final int comma = value.indexOf(',');
                return comma > 0 ? value.substring(0, comma).trim() : value.trim();
            }
        }
        // Check X-Real-IP
        for (Header h : headers.find("x-real-ip")) {
            return h.getValue();
        }
        // Fallback to remote address
        return remoteAddress;
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
            // For Bearer tokens, we don't extract username (would need token validation)
        }
        return Optional.empty();
    }

    /**
     * Parse user agent string into ECS components.
     * Basic implementation focusing on common package managers and CI/CD tools.
     * 
     * @param ua User agent string
     * @return Parsed user agent info
     */
    private static UserAgentInfo parseUserAgent(final String ua) {
        final UserAgentInfo info = new UserAgentInfo();
        
        if (ua == null || ua.isEmpty()) {
            return info;
        }
        
        // Common package manager patterns
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
        
        // Extract OS information
        if (ua.contains("Linux")) {
            info.osName = "Linux";
        } else if (ua.contains("Windows")) {
            info.osName = "Windows";
        } else if (ua.contains("Mac OS X") || ua.contains("Darwin")) {
            info.osName = "macOS";
        } else if (ua.contains("FreeBSD")) {
            info.osName = "FreeBSD";
        }
        
        // Extract Java version if present
        if (ua.contains("Java/")) {
            final int start = ua.indexOf("Java/") + 5;
            final int end = findVersionEnd(ua, start);
            if (end > start) {
                info.osVersion = ua.substring(start, end);
            }
        }
        
        return info;
    }
    
    /**
     * Extract version from user agent string.
     */
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
    
    /**
     * Find end of version string (space, semicolon, or parenthesis).
     */
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

    /**
     * Parsed user agent information.
     */
    private static final class UserAgentInfo {
        String name;
        String version;
        String osName;
        String osVersion;
        String deviceName;
    }
}
