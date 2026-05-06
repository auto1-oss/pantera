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
package com.auto1.pantera.prefetch.parser;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * {@link NpmMetadataLookup} that resolves npm version ranges against the
 * locally-cached packument(s) under one or more npm-proxy storages.
 *
 * <p>The lookup never issues an upstream metadata fetch — pre-fetch must
 * stay strictly local. If the metadata is not cached, an empty
 * {@link Optional} is returned and the caller skips the dependency.</p>
 *
 * <p>For each candidate npm-proxy storage the lookup tries the abbreviated
 * packument first ({@code <name>/meta.abbreviated.json}, ~80&ndash;90% smaller)
 * and falls back to the full {@code <name>/meta.json} when the abbreviated
 * file is absent (e.g., older cache entries). Both files contain a
 * {@code versions} object keyed by concrete version, which is all this
 * resolver needs.</p>
 *
 * <p>Range parsing uses {@code semver4j}'s {@link Requirement#buildNPM(String)}
 * which understands the npm semver mini-spec: caret ({@code ^1.2.3}), tilde
 * ({@code ~1.2.3}), exact ({@code 1.2.3}), comparators
 * ({@code >=1.0.0 <2.0.0}), alternatives ({@code 1.x || 2.x}), {@code *},
 * {@code latest}, hyphen ranges, etc. Prerelease versions are excluded
 * unless the requested range explicitly carries a prerelease tag, matching
 * npm's own resolver semantics.</p>
 *
 * @since 2.2.0
 */
public final class CachedNpmMetadataLookup implements NpmMetadataLookup {

    /** Logger name used for diagnostic events emitted by this lookup. */
    private static final String LOGGER = "com.auto1.pantera.prefetch.CachedNpmMetadataLookup";

    /** Shared, thread-safe Jackson reader; hoisted to avoid per-call allocation. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * How long we wait for a Storage value/exists call to complete before
     * giving up and returning empty. The lookup runs on the cache-write
     * thread (an FJP common-pool worker invoked from the dispatcher), so a
     * stuck storage call must NEVER stall a cache write indefinitely. The
     * default 250&nbsp;ms is a generous bound for FileStorage on a hot SSD
     * (microsecond-scale) and S3 (sub-100&nbsp;ms). On timeout the
     * dependency is silently skipped — pre-fetch is best effort.
     */
    private static final long IO_TIMEOUT_MS = 250L;

    /**
     * Snapshot supplier of npm-proxy storages to consult, keyed by repo
     * name (insertion order is the lookup priority). Refreshed on every
     * {@link #resolve(String, String)} so live config reloads (a new
     * npm-proxy added at runtime) are picked up without reconstruction.
     */
    private final Supplier<List<NamedStorage>> storagesSupplier;

    /**
     * @param storagesSupplier Snapshot of npm-proxy storages to consult, in
     *                         lookup-priority order. Called once per
     *                         {@link #resolve(String, String)} invocation.
     */
    public CachedNpmMetadataLookup(final Supplier<List<NamedStorage>> storagesSupplier) {
        this.storagesSupplier = storagesSupplier;
    }

    @Override
    public Optional<String> resolve(final String packageName, final String versionRange) {
        if (packageName == null || packageName.isEmpty()
            || versionRange == null || versionRange.isEmpty()) {
            return Optional.empty();
        }
        final Requirement req;
        try {
            req = Requirement.buildNPM(versionRange);
        } catch (final SemverException ex) {
            // Unparseable range (e.g. git URL, tag we don't know). Skip.
            return Optional.empty();
        }
        final boolean rangeAllowsPrerelease = looksLikePrereleaseRange(versionRange);
        final List<NamedStorage> stores = this.storagesSupplier.get();
        if (stores == null || stores.isEmpty()) {
            return Optional.empty();
        }
        for (final NamedStorage entry : stores) {
            final Optional<String> hit = this.lookupIn(
                entry, packageName, req, rangeAllowsPrerelease
            );
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    private Optional<String> lookupIn(
        final NamedStorage entry,
        final String packageName,
        final Requirement req,
        final boolean rangeAllowsPrerelease
    ) {
        try {
            final byte[] metaBytes = readMeta(entry.storage(), packageName);
            if (metaBytes == null) {
                return Optional.empty();
            }
            return pickVersion(metaBytes, req, rangeAllowsPrerelease);
        } catch (final TimeoutException tex) {
            EcsLogger.warn(LOGGER)
                .message("npm metadata read timed out; skipping dependency")
                .eventCategory("file")
                .eventAction("read")
                .eventOutcome("failure")
                .field("repository.name", entry.repoName())
                .field("package.name", packageName)
                .field("error.type", "Timeout")
                .log();
            return Optional.empty();
        } catch (final Exception ex) {
            // ANY failure path returns empty so the parser silently drops
            // the dependency; pre-fetch is best-effort.
            EcsLogger.debug(LOGGER)
                .message("npm metadata lookup miss: " + ex.getMessage())
                .eventCategory("file")
                .eventAction("read")
                .eventOutcome("failure")
                .field("repository.name", entry.repoName())
                .field("package.name", packageName)
                .field("error.type", ex.getClass().getName())
                .log();
            return Optional.empty();
        }
    }

    /**
     * Read the abbreviated packument first, fall back to the full one.
     * Returns {@code null} when neither file exists in the storage.
     */
    private static byte[] readMeta(final Storage storage, final String packageName)
        throws Exception {
        final Key abbrev = new Key.From(packageName, "meta.abbreviated.json");
        final Key full = new Key.From(packageName, "meta.json");
        final CompletableFuture<Boolean> abbrevExists = storage.exists(abbrev);
        if (Boolean.TRUE.equals(abbrevExists.get(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS))) {
            return readBytes(storage, abbrev);
        }
        final CompletableFuture<Boolean> fullExists = storage.exists(full);
        if (Boolean.TRUE.equals(fullExists.get(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS))) {
            return readBytes(storage, full);
        }
        return null;
    }

    private static byte[] readBytes(final Storage storage, final Key key) throws Exception {
        final Content value = storage.value(key).get(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return value.asBytesFuture().get(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Walk the {@code versions} object in the packument JSON, filter by the
     * requirement, and return the highest satisfying version (descending
     * semver order, prereleases excluded unless explicitly opted in).
     */
    static Optional<String> pickVersion(
        final byte[] metaBytes,
        final Requirement req,
        final boolean allowPrerelease
    ) throws java.io.IOException {
        final JsonNode root = JSON.readTree(metaBytes);
        final JsonNode versions = root.get("versions");
        if (versions == null || !versions.isObject()) {
            return Optional.empty();
        }
        Semver best = null;
        String bestStr = null;
        final Iterator<String> names = versions.fieldNames();
        while (names.hasNext()) {
            final String candidate = names.next();
            final Semver sem;
            try {
                sem = new Semver(candidate, Semver.SemverType.NPM);
            } catch (final SemverException ex) {
                continue;
            }
            if (!allowPrerelease && isPrerelease(sem)) {
                continue;
            }
            if (!req.isSatisfiedBy(sem)) {
                continue;
            }
            if (best == null || sem.isGreaterThan(best)) {
                best = sem;
                bestStr = candidate;
            }
        }
        return Optional.ofNullable(bestStr);
    }

    /**
     * A Semver is a prerelease when it carries any suffix tokens (e.g.
     * {@code 1.0.0-beta}, {@code 2.0.0-rc.1}). semver4j's {@code isStable()}
     * does NOT mean "not a prerelease" — it returns false for {@code 0.x.y}
     * — so we check suffix tokens directly.
     */
    private static boolean isPrerelease(final Semver sem) {
        final String[] suffixes = sem.getSuffixTokens();
        return suffixes != null && suffixes.length > 0;
    }

    /**
     * Heuristic: does the requested range include a prerelease tag (a
     * literal {@code -alpha} / {@code -rc} / {@code -beta} segment)? If so
     * we relax the prerelease filter so e.g. {@code "^4.0.0-rc.1"} can
     * resolve to {@code 4.0.0-rc.5}. Without this guard, semver4j would
     * still match the version but our prerelease filter would drop it.
     */
    private static boolean looksLikePrereleaseRange(final String range) {
        // The simplest signal: a hyphen followed by a non-digit is a
        // prerelease tag (npm hyphen ranges are " - " separated, which is
        // why we require the hyphen to be NOT preceded by whitespace).
        final int len = range.length();
        for (int idx = 0; idx < len - 1; idx++) {
            if (range.charAt(idx) == '-'
                && (idx == 0 || range.charAt(idx - 1) != ' ')
                && !Character.isDigit(range.charAt(idx + 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pair of (repoName, storage) used by the lookup. Carrying the repo
     * name keeps the diagnostic logs useful when a lookup fails.
     */
    public record NamedStorage(String repoName, Storage storage) {
    }

    // package-private hook for tests that want to round-trip a JSON byte[]
    private static byte[] utf8(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
