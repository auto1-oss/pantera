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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.cache.ProxyCacheWriter.IntegrityAuditor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProxyCacheWriter.IntegrityAuditor} — the one-shot admin
 * tool that scans a proxy cache for primary/sidecar drift and optionally
 * evicts offenders (§9.5 "Healing stale pairs").
 */
final class CacheIntegrityAuditTest {

    @Test
    @DisplayName("--dry-run reports mismatches but does not delete")
    void dryRun_reportsMismatches_butDoesNotDelete(@TempDir final Path tempDir) {
        final FileStorage storage = new FileStorage(tempDir);
        final Key primary = new Key.From("com/example/foo/1.0/foo-1.0.jar");
        final byte[] primaryBytes = "some jar bytes".getBytes(StandardCharsets.UTF_8);
        storage.save(primary, new Content.From(primaryBytes)).join();
        // Seed an intentionally WRONG .sha1 sidecar (the production symptom).
        storage.save(
            new Key.From(primary.string() + ".sha1"),
            new Content.From("ffffffffffffffffffffffffffffffffffffffff"
                .getBytes(StandardCharsets.UTF_8))
        ).join();

        final IntegrityAuditor.Report report = IntegrityAuditor.run(
            storage, "maven-proxy", false
        );

        assertFalse(report.clean(), "mismatches detected");
        assertThat("one mismatched primary", report.mismatches(), hasSize(1));
        assertThat(
            "sha1 algorithm flagged",
            report.mismatches().get(0).algorithms(),
            hasSize(1)
        );
        // Files still present (dry-run does NOT evict).
        assertTrue(storage.exists(primary).join(), "primary still present");
        assertTrue(
            storage.exists(new Key.From(primary.string() + ".sha1")).join(),
            "sidecar still present"
        );
    }

    @Test
    @DisplayName("--fix evicts mismatched pairs")
    void fix_evictsMismatchedPairs(@TempDir final Path tempDir) {
        final FileStorage storage = new FileStorage(tempDir);
        final Key primary = new Key.From("com/example/bar/2.0/bar-2.0.pom");
        final byte[] primaryBytes = "pom bytes".getBytes(StandardCharsets.UTF_8);
        storage.save(primary, new Content.From(primaryBytes)).join();
        storage.save(
            new Key.From(primary.string() + ".sha1"),
            new Content.From("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
                .getBytes(StandardCharsets.UTF_8))
        ).join();
        storage.save(
            new Key.From(primary.string() + ".md5"),
            new Content.From("cafebabecafebabecafebabecafebabe"
                .getBytes(StandardCharsets.UTF_8))
        ).join();

        final IntegrityAuditor.Report report = IntegrityAuditor.run(
            storage, "maven-proxy", true
        );

        assertFalse(report.clean(), "mismatches detected before fix");
        // Files evicted after --fix.
        assertFalse(storage.exists(primary).join(), "primary evicted");
        assertFalse(
            storage.exists(new Key.From(primary.string() + ".sha1")).join(),
            "sha1 sidecar evicted"
        );
        assertFalse(
            storage.exists(new Key.From(primary.string() + ".md5")).join(),
            "md5 sidecar evicted"
        );
    }

    @Test
    @DisplayName("clean cache → empty report, exit code 0 rendered by CLI")
    void cleanCache_emitsEmptyReport(@TempDir final Path tempDir) {
        final FileStorage storage = new FileStorage(tempDir);
        final Key primary = new Key.From("com/example/clean/1.0/clean-1.0.jar");
        final byte[] bytes = "consistent".getBytes(StandardCharsets.UTF_8);
        storage.save(primary, new Content.From(bytes)).join();
        storage.save(
            new Key.From(primary.string() + ".sha1"),
            new Content.From(sha1Hex(bytes).getBytes(StandardCharsets.UTF_8))
        ).join();
        storage.save(
            new Key.From(primary.string() + ".sha256"),
            new Content.From(sha256Hex(bytes).getBytes(StandardCharsets.UTF_8))
        ).join();

        final IntegrityAuditor.Report report = IntegrityAuditor.run(
            storage, "maven-proxy", true
        );

        assertTrue(report.clean(), "no mismatches");
        assertThat("scanned 1 primary", report.scanned(), is(1));
        assertTrue(storage.exists(primary).join(), "primary preserved");
    }

    @Test
    @DisplayName("sidecar missing on a primary does not count as a mismatch")
    void sidecarMissing_noMismatch(@TempDir final Path tempDir) {
        final FileStorage storage = new FileStorage(tempDir);
        final Key primary = new Key.From("com/example/nosidecar/1.0/nosidecar-1.0.jar");
        storage.save(primary, new Content.From("bytes".getBytes(StandardCharsets.UTF_8))).join();

        final IntegrityAuditor.Report report = IntegrityAuditor.run(
            storage, "maven-proxy", false
        );

        assertTrue(report.clean(), "no sidecar == no mismatch");
        assertThat("1 primary scanned", report.scanned(), is(1));
    }

    // ===== helpers =====

    private static String sha1Hex(final byte[] body) {
        return hex("SHA-1", body);
    }

    private static String sha256Hex(final byte[] body) {
        return hex("SHA-256", body);
    }

    private static String hex(final String algo, final byte[] body) {
        try {
            final MessageDigest md = MessageDigest.getInstance(algo);
            return HexFormat.of().formatHex(md.digest(body));
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
