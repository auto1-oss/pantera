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
package com.auto1.pantera.security;

import com.auto1.pantera.http.log.EcsLogger;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;

/**
 * Minimal client for the OSV.dev <em>v1/query</em> endpoint. T-S08 of
 * {@code analysis/plan/v2/IMPLEMENTATION.md}.
 *
 * <p>OSV.dev (Open Source Vulnerabilities, an OpenSSF project, see
 * <a href="https://osv.dev/">osv.dev</a>) federates GitHub Security
 * Advisories, the npm advisories DB, the Go vulnerability database,
 * PyPA security advisories, and several others into a single REST API.
 * Pantera queries it after every primary cache write on repos that opt
 * in via {@code scan_for_vulnerabilities: true}.</p>
 *
 * <p>Wire format reference (April 2025 revision):</p>
 * <pre>{@code
 * POST https://api.osv.dev/v1/query
 * { "package": { "name": "log4j-core", "ecosystem": "Maven" },
 *   "version": "2.14.1" }
 *
 * 200 OK
 * { "vulns": [{
 *     "id": "GHSA-jfh8-c2jp-5v3q",
 *     "summary": "Remote code execution in Apache Log4j",
 *     "aliases": ["CVE-2021-44228"],
 *     "database_specific": { "severity": "CRITICAL" },
 *     "affected": [...]
 *   }]
 * }
 * }</pre>
 *
 * <p>Responses with no matching vulnerabilities return an empty (or
 * absent) {@code vulns} array — this client maps that to
 * {@code List.of()} and the scanner records a "clean scan" sentinel
 * row in the DB.</p>
 *
 * @since 2.2.0
 */
public final class OsvDevClient {

    /**
     * Default endpoint. Override only for tests pointing at a mock server.
     */
    public static final String DEFAULT_ENDPOINT = "https://api.osv.dev/v1/query";

    /**
     * Default per-request timeout. OSV.dev typically responds in
     * &lt; 1 s; 30 s gives plenty of headroom for occasional slow
     * queries without blocking the scanner queue.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Underlying HTTP client. {@link HttpClient} is reusable; one
     * instance per OsvDevClient is fine.
     */
    private final HttpClient http;

    /**
     * OSV.dev query URL.
     */
    private final URI endpoint;

    /**
     * Per-request timeout.
     */
    private final Duration timeout;

    /**
     * Construct a client targeting production OSV.dev with sensible
     * defaults.
     */
    public OsvDevClient() {
        this(
            HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
            URI.create(DEFAULT_ENDPOINT),
            DEFAULT_TIMEOUT
        );
    }

    /**
     * Constructor for tests: inject a mock {@link HttpClient}.
     *
     * @param http     HTTP client (must be non-null).
     * @param endpoint OSV.dev endpoint (typically a test fixture URL).
     * @param timeout  Per-request timeout.
     */
    public OsvDevClient(
        final HttpClient http,
        final URI endpoint,
        final Duration timeout
    ) {
        this.http = Objects.requireNonNull(http, "http client must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    /**
     * Query OSV.dev for vulnerabilities affecting a specific
     * {@code (ecosystem, package, version)} tuple.
     *
     * @param ecosystem OSV ecosystem identifier
     *     ({@code Maven, npm, PyPI, Go, NuGet, RubyGems, Hex,
     *     Packagist, Debian, ...}). See
     *     <a href="https://ossf.github.io/osv-schema/#ecosystem-field">
     *     OSV schema</a>.
     * @param name      Package coordinate (e.g.
     *     {@code org.apache.logging.log4j:log4j-core},
     *     {@code lodash}).
     * @param version   Exact version.
     * @return List of zero or more {@link Vulnerability} records.
     * @throws OsvException on transport failure, non-2xx response,
     *     or malformed JSON.
     */
    public List<Vulnerability> query(
        final String ecosystem, final String name, final String version
    ) throws OsvException {
        Objects.requireNonNull(ecosystem, "ecosystem");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        final String payload = Json.createObjectBuilder()
            .add("package", Json.createObjectBuilder()
                .add("name", name)
                .add("ecosystem", ecosystem))
            .add("version", version)
            .build()
            .toString();
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(this.endpoint)
            .timeout(this.timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "pantera/2.2.0")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        final HttpResponse<String> response;
        try {
            response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final java.io.IOException ioe) {
            throw new OsvException("OSV.dev transport failed", ioe);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OsvException("OSV.dev request interrupted", ie);
        }
        if (response.statusCode() / 100 != 2) {
            throw new OsvException(
                "OSV.dev returned non-2xx status " + response.statusCode()
                    + " for " + ecosystem + "/" + name + "@" + version
            );
        }
        return parseVulnerabilities(response.body());
    }

    /**
     * Parse the OSV.dev response body into a list of
     * {@link Vulnerability} records. Tolerant of missing fields —
     * OSV records vary between ecosystems and we only consume a small
     * subset.
     *
     * @param body Response body (JSON).
     * @return Parsed list (possibly empty).
     * @throws OsvException on malformed JSON.
     */
    private static List<Vulnerability> parseVulnerabilities(final String body) throws OsvException {
        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            final JsonObject root = reader.readObject();
            if (!root.containsKey("vulns")) {
                return Collections.emptyList();
            }
            final JsonArray vulns = root.getJsonArray("vulns");
            if (vulns == null || vulns.isEmpty()) {
                return Collections.emptyList();
            }
            final List<Vulnerability> out = new ArrayList<>(vulns.size());
            for (final JsonValue v : vulns) {
                if (v instanceof JsonObject obj) {
                    out.add(toVulnerability(obj));
                }
            }
            return out;
        } catch (final RuntimeException ex) {
            EcsLogger.warn("com.auto1.pantera.security")
                .message("Failed to parse OSV.dev response")
                .eventCategory("intrusion_detection")
                .eventAction("osv_parse")
                .eventOutcome("failure")
                .error(ex)
                .log();
            throw new OsvException("Failed to parse OSV.dev response", ex);
        }
    }

    /**
     * Convert one OSV record into a {@link Vulnerability}.
     */
    private static Vulnerability toVulnerability(final JsonObject obj) {
        final String id = stringOrNull(obj, "id");
        final String summary = stringOrNull(obj, "summary");
        // CVE alias preference: OSV records carry both a primary id
        // (e.g. GHSA-xxx) and an aliases array that usually contains
        // the CVE. Prefer the CVE when present.
        String cveId = id;
        if (obj.containsKey("aliases")) {
            final JsonArray aliases = obj.getJsonArray("aliases");
            for (final JsonValue a : aliases) {
                if (a instanceof JsonString js && js.getString().startsWith("CVE-")) {
                    cveId = js.getString();
                    break;
                }
            }
        }
        String severity = null;
        if (obj.containsKey("database_specific")) {
            final JsonObject db = obj.getJsonObject("database_specific");
            severity = stringOrNull(db, "severity");
        }
        return new Vulnerability(cveId, severity, summary, obj.toString());
    }

    private static String stringOrNull(final JsonObject obj, final String key) {
        if (!obj.containsKey(key) || obj.isNull(key)) {
            return null;
        }
        final JsonValue v = obj.get(key);
        if (v instanceof JsonString js) {
            return js.getString();
        }
        return v.toString();
    }

    /**
     * Parsed CVE record. {@code rawPayload} carries the full OSV record
     * as JSON for downstream persistence in {@code osv_payload}.
     *
     * @param cveId      Preferred id — CVE when one is in the OSV
     *     aliases, otherwise the OSV id (GHSA-..., PYSEC-..., ...).
     * @param severity   OSV {@code database_specific.severity}, may be
     *     {@code null} for sources that don't classify.
     * @param summary    One-line human description.
     * @param rawPayload Original OSV record as a JSON string.
     */
    public record Vulnerability(
        String cveId,
        String severity,
        String summary,
        String rawPayload
    ) {
    }

    /**
     * Thrown on any OSV.dev failure (transport, non-2xx, parse).
     * Callers (the scanner worker) treat this as a retryable failure.
     */
    public static final class OsvException extends Exception {

        private static final long serialVersionUID = 1L;

        public OsvException(final String message) {
            super(message);
        }

        public OsvException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
