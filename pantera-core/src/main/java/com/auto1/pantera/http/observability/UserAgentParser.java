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

/**
 * User-Agent string parser producing ECS {@code user_agent.*} sub-fields.
 *
 * <p>Recognises the User-Agent shapes Pantera sees in practice from package-
 * manager clients (Maven, npm, pip, Docker, Go, Gradle, Composer, NuGet) and
 * from generic HTTP tools (curl, wget). Also extracts the host OS family
 * (Linux / Windows / macOS / FreeBSD) and, where present, the {@code Java/x.y.z}
 * runtime version — which conventionally maps to ECS
 * {@code user_agent.os.version} for JVM-based clients.
 *
 * <p>Factored out of the legacy {@code EcsLogEvent} in v2.2.0 (WI-post-03b)
 * so the new {@link StructuredLogger#access()} tier can re-emit the
 * {@code user_agent.name} / {@code .version} / {@code .os.name} / {@code .os.version}
 * / {@code .device.name} sub-fields that Kibana dashboards filter on. The
 * parsing logic is preserved verbatim from the original EcsLogEvent
 * implementation — no behavioural change.
 *
 * <p>All methods are null-safe and side-effect free.
 *
 * @since 2.2.0
 */
public final class UserAgentParser {

    private UserAgentParser() {
        // utility — not instantiable
    }

    /**
     * Parse a User-Agent string into an {@link UserAgentInfo}. Null or empty
     * input returns an all-{@code null} {@code UserAgentInfo}; unrecognised UAs
     * return an info with only the OS fields populated (and even those only
     * when the UA string contains one of the recognised OS tokens).
     *
     * @param ua the raw {@code User-Agent} header value — may be {@code null}
     * @return a non-null {@link UserAgentInfo}; fields are {@code null} when
     *         the parser could not determine them
     */
    public static UserAgentInfo parse(final String ua) {
        String name = null;
        String version = null;
        String osName = null;
        String osVersion = null;
        final String deviceName = null;

        if (ua == null || ua.isEmpty()) {
            return new UserAgentInfo(null, null, null, null, null);
        }

        if (ua.startsWith("Maven/")) {
            name = "Maven";
            version = extractVersion(ua, "Maven/");
        } else if (ua.startsWith("npm/")) {
            name = "npm";
            version = extractVersion(ua, "npm/");
        } else if (ua.startsWith("pip/")) {
            name = "pip";
            version = extractVersion(ua, "pip/");
        } else if (ua.contains("Docker-Client/")) {
            name = "Docker";
            version = extractVersion(ua, "Docker-Client/");
        } else if (ua.startsWith("Go-http-client/")) {
            name = "Go";
            version = extractVersion(ua, "Go-http-client/");
        } else if (ua.startsWith("Gradle/")) {
            name = "Gradle";
            version = extractVersion(ua, "Gradle/");
        } else if (ua.contains("Composer/")) {
            name = "Composer";
            version = extractVersion(ua, "Composer/");
        } else if (ua.startsWith("NuGet")) {
            name = "NuGet";
            if (ua.contains("/")) {
                version = extractVersion(ua, "NuGet Command Line/");
            }
        } else if (ua.contains("curl/")) {
            name = "curl";
            version = extractVersion(ua, "curl/");
        } else if (ua.contains("wget/")) {
            name = "wget";
            version = extractVersion(ua, "wget/");
        }

        if (ua.contains("Linux")) {
            osName = "Linux";
        } else if (ua.contains("Windows")) {
            osName = "Windows";
        } else if (ua.contains("Mac OS X") || ua.contains("Darwin")) {
            osName = "macOS";
        } else if (ua.contains("FreeBSD")) {
            osName = "FreeBSD";
        }

        if (ua.contains("Java/")) {
            final int start = ua.indexOf("Java/") + 5;
            final int end = findVersionEnd(ua, start);
            if (end > start) {
                osVersion = ua.substring(start, end);
            }
        }

        return new UserAgentInfo(name, version, osName, osVersion, deviceName);
    }

    private static String extractVersion(final String ua, final String prefix) {
        final int start = ua.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        final int versionStart = start + prefix.length();
        final int versionEnd = findVersionEnd(ua, versionStart);
        if (versionEnd <= versionStart) {
            return null;
        }
        return ua.substring(versionStart, versionEnd);
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

    /**
     * Parsed ECS {@code user_agent.*} sub-fields. All fields are {@code null}
     * when the parser could not determine them — callers must null-check before
     * emitting into a log payload.
     *
     * @param name       ECS {@code user_agent.name} — client family
     *                   (e.g. {@code Maven}, {@code npm}, {@code Docker}).
     * @param version    ECS {@code user_agent.version} — client version
     *                   (e.g. {@code 3.9.6}).
     * @param osName     ECS {@code user_agent.os.name}
     *                   ({@code Linux} / {@code Windows} / {@code macOS} / {@code FreeBSD}).
     * @param osVersion  ECS {@code user_agent.os.version} — for JVM clients,
     *                   the {@code Java/x.y.z} runtime version.
     * @param deviceName ECS {@code user_agent.device.name} — reserved; not
     *                   populated by the current parser (always {@code null}).
     */
    public record UserAgentInfo(
        String name,
        String version,
        String osName,
        String osVersion,
        String deviceName
    ) {
    }
}
