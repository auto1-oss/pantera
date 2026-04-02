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
package com.auto1.pantera.vuln.backend;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.vuln.ScannerBackend;
import com.auto1.pantera.vuln.VulnerabilityFinding;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * {@link ScannerBackend} backed by the Grype CLI.
 *
 * <p>Runs {@code grype dir:<scanDir> -o json --quiet} against the prepared
 * scan directory and parses the JSON findings into {@link VulnerabilityFinding} objects.
 *
 * <p>Grype JSON output structure:
 * <pre>
 * {
 *   "matches": [{
 *     "vulnerability": {
 *       "id": "CVE-2021-44228",
 *       "severity": "Critical",
 *       "description": "Apache Log4j2...",
 *       "fix": { "versions": ["2.17.1"] }
 *     },
 *     "artifact": {
 *       "name": "log4j-core",
 *       "version": "2.14.1"
 *     }
 *   }]
 * }
 * </pre>
 *
 * @since 2.2.0
 */
public final class GrypeScannerBackend implements ScannerBackend {

    /**
     * Scanner identifier written into scan reports.
     */
    private static final String NAME = "grype";

    /**
     * Path or binary name of the Grype CLI.
     */
    private final String scannerPath;

    /**
     * Ctor.
     * @param scannerPath Path or binary name for Grype (e.g. {@code "grype"} or
     *                    {@code "/usr/local/bin/grype"})
     */
    public GrypeScannerBackend(final String scannerPath) {
        this.scannerPath = scannerPath;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<VulnerabilityFinding> scan(
        final Path scanDir, final int timeoutSeconds
    ) throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder(
            this.scannerPath,
            String.format("dir:%s", scanDir.toAbsolutePath()),
            "-o", "json",
            "--quiet"
        );
        pb.redirectErrorStream(false);
        final Process proc = pb.start();
        // Drain stdout/stderr concurrently to prevent pipe-buffer deadlock on large output.
        final java.util.concurrent.Future<byte[]> stdoutFuture =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().submit(
                () -> proc.getInputStream().readAllBytes()
            );
        final java.util.concurrent.Future<byte[]> stderrFuture =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().submit(
                () -> proc.getErrorStream().readAllBytes()
            );
        final boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            EcsLogger.warn("com.auto1.pantera.vuln")
                .message("Grype scan timed out")
                .eventCategory("security")
                .eventAction("vulnerability_scan")
                .eventOutcome("timeout")
                .field("target", scanDir.toString())
                .field("timeout_seconds", timeoutSeconds)
                .log();
            return List.of();
        }
        final String stdout;
        final String stderr;
        try {
            stdout = new String(stdoutFuture.get(), StandardCharsets.UTF_8);
            stderr = new String(stderrFuture.get(), StandardCharsets.UTF_8);
        } catch (final java.util.concurrent.ExecutionException ex) {
            throw new IOException("Failed to read scanner output", ex);
        }
        if (proc.exitValue() != 0) {
            EcsLogger.warn("com.auto1.pantera.vuln")
                .message("Grype exited with non-zero code")
                .eventCategory("security")
                .eventAction("vulnerability_scan")
                .eventOutcome("failure")
                .field("exit_code", proc.exitValue())
                .field("stderr", stderr.length() > 500 ? stderr.substring(0, 500) : stderr)
                .log();
        }
        return stdout.isBlank() ? List.of() : parseFindings(stdout);
    }

    /**
     * Parse Grype JSON output into a list of findings.
     * @param json Raw Grype stdout JSON
     * @return Parsed findings (may be empty)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static List<VulnerabilityFinding> parseFindings(final String json) {
        final List<VulnerabilityFinding> findings = new ArrayList<>();
        try {
            final JsonObject root = new JsonObject(json);
            final JsonArray matches = root.getJsonArray("matches");
            if (matches == null) {
                return findings;
            }
            for (int i = 0; i < matches.size(); i++) {
                final JsonObject match = matches.getJsonObject(i);
                if (match == null) {
                    continue;
                }
                final JsonObject vuln = match.getJsonObject("vulnerability");
                final JsonObject artifact = match.getJsonObject("artifact");
                if (vuln == null || artifact == null) {
                    continue;
                }
                final String cveId = vuln.getString("id", "UNKNOWN");
                final String severity = normalizeSeverity(vuln.getString("severity", "UNKNOWN"));
                final String pkgName = artifact.getString("name", "");
                final String installed = artifact.getString("version", "");
                final String fixed = extractFixedVersion(vuln.getJsonObject("fix"));
                final String title = vuln.getString("description", "");
                findings.add(new VulnerabilityFinding(
                    cveId, severity, pkgName, installed, fixed,
                    title.length() > 500 ? title.substring(0, 500) : title
                ));
            }
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.vuln")
                .message("Failed to parse Grype JSON output")
                .eventCategory("security")
                .eventAction("vulnerability_parse")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
        return findings;
    }

    /**
     * Extract the first fixed version from the Grype fix object.
     * @param fix The {@code vulnerability.fix} JSON object (may be null)
     * @return First fixed version string, or empty string if unavailable
     */
    private static String extractFixedVersion(final JsonObject fix) {
        if (fix == null) {
            return "";
        }
        final JsonArray versions = fix.getJsonArray("versions");
        if (versions == null || versions.isEmpty()) {
            return "";
        }
        final String first = versions.getString(0);
        return first != null ? first : "";
    }

    /**
     * Normalize a raw severity string from Grype into one of the canonical values.
     * Grype uses title-case (e.g. "Critical") while our canonical form is upper-case.
     * @param raw Raw severity from Grype (e.g. "Critical", "HIGH")
     * @return Normalized severity string
     */
    private static String normalizeSeverity(final String raw) {
        if (raw == null) {
            return "UNKNOWN";
        }
        return switch (raw.toUpperCase(Locale.US)) {
            case "CRITICAL"   -> "CRITICAL";
            case "HIGH"       -> "HIGH";
            case "MEDIUM"     -> "MEDIUM";
            case "LOW"        -> "LOW";
            case "NEGLIGIBLE" -> "LOW";
            default           -> "UNKNOWN";
        };
    }
}
