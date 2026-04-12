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
 * {@link ScannerBackend} backed by the Trivy CLI.
 *
 * <p>Runs {@code trivy fs --scanners vuln --format json --quiet} against the
 * prepared scan directory and parses the JSON findings into
 * {@link VulnerabilityFinding} objects.
 *
 * <p>To switch to a different scanner (Grype, OSV-Scanner, etc.), implement
 * {@link ScannerBackend} and wire the new backend in {@code AsyncApiVerticle}.
 *
 * @since 2.2.0
 */
public final class TrivyScannerBackend implements ScannerBackend {

    /**
     * Scanner identifier written into scan reports.
     */
    private static final String NAME = "trivy";

    /**
     * Path or binary name of the Trivy CLI.
     */
    private final String scannerPath;

    /**
     * Ctor.
     * @param scannerPath Path or binary name for Trivy (e.g. {@code "trivy"} or
     *                    {@code "/usr/local/bin/trivy"})
     */
    public TrivyScannerBackend(final String scannerPath) {
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
            "fs",
            "--scanners", "vuln",
            "--format", "json",
            "--quiet",
            "--exit-code", "0",
            scanDir.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(false);
        final Process proc = pb.start();
        // Drain stdout and stderr in background threads to prevent pipe-buffer deadlock.
        // If the scanner produces large output (e.g. 200KB+ of CVE JSON), the OS pipe
        // buffer fills and the scanner process blocks waiting for Java to read — while
        // Java is blocked in waitFor(). Draining concurrently breaks the deadlock.
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
                .message("Trivy scan timed out")
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
                .message("Trivy exited with non-zero code")
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
     * Parse Trivy JSON output into a list of findings.
     * @param json Raw Trivy stdout JSON
     * @return Parsed findings (may be empty)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static List<VulnerabilityFinding> parseFindings(final String json) {
        final List<VulnerabilityFinding> findings = new ArrayList<>();
        try {
            final JsonObject root = new JsonObject(json);
            final JsonArray results = root.getJsonArray("Results");
            if (results == null) {
                return findings;
            }
            for (int ri = 0; ri < results.size(); ri++) {
                final JsonObject result = results.getJsonObject(ri);
                if (result == null) {
                    continue;
                }
                final JsonArray vulns = result.getJsonArray("Vulnerabilities");
                if (vulns == null) {
                    continue;
                }
                for (int vi = 0; vi < vulns.size(); vi++) {
                    final JsonObject vuln = vulns.getJsonObject(vi);
                    if (vuln == null) {
                        continue;
                    }
                    findings.add(new VulnerabilityFinding(
                        vuln.getString("VulnerabilityID", "UNKNOWN"),
                        normalizeSeverity(vuln.getString("Severity", "UNKNOWN")),
                        vuln.getString("PkgName", ""),
                        vuln.getString("InstalledVersion", ""),
                        vuln.getString("FixedVersion", ""),
                        vuln.getString("Title", "")
                    ));
                }
            }
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.vuln")
                .message("Failed to parse Trivy JSON output")
                .eventCategory("security")
                .eventAction("vulnerability_parse")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
        return findings;
    }

    /**
     * Normalize a raw severity string from Trivy into one of the canonical values.
     * @param raw Raw severity from Trivy (e.g. "CRITICAL", "medium")
     * @return Normalized severity string
     */
    private static String normalizeSeverity(final String raw) {
        if (raw == null) {
            return "UNKNOWN";
        }
        return switch (raw.toUpperCase(Locale.US)) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH"     -> "HIGH";
            case "MEDIUM"   -> "MEDIUM";
            case "LOW"      -> "LOW";
            default         -> "UNKNOWN";
        };
    }
}
