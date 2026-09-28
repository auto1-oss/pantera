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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.composer.JsonPackage;
import com.auto1.pantera.composer.Name;
import com.auto1.pantera.composer.Packages;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.misc.StorageExecutors;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

/**
 * Immutability check for an upload to a local Composer repository.
 *
 * <p>A published release ({@code 1.0.0}, {@code 2.1.0-RC1}) is immutable:
 * consumers pin it in {@code composer.lock} together with its
 * {@code dist.shasum}, so a swapped archive would break their installs.
 * Re-uploading it with
 * different content is a conflict; re-uploading identical content is
 * idempotent. Dev branches ({@code dev-*}, {@code *-dev}) move by design
 * and may be overwritten.</p>
 *
 * @since 2.2.9
 */
final class ReleaseGuard {

    /**
     * Outcome of the check.
     */
    enum Verdict {
        /**
         * Not published yet (or a dev branch): store it.
         */
        NEW,

        /**
         * Already published with the same content: nothing to do.
         */
        IDENTICAL,

        /**
         * Already published with different content: reject.
         */
        CONFLICT
    }

    /**
     * Largest stored archive the guard reads to compare with an upload
     * (compressed size). Larger published releases are not read: any
     * re-upload of them is a conflict.
     */
    private static final long MAX_STORED = 256L * 1024 * 1024;

    /**
     * Repository.
     */
    private final Repository repository;

    /**
     * Executor for fingerprinting.
     */
    private final Executor executor;

    /**
     * Ctor.
     *
     * @param repository Repository
     */
    ReleaseGuard(final Repository repository) {
        this(repository, StorageExecutors.WRITE);
    }

    /**
     * Ctor.
     *
     * @param repository Repository
     * @param executor Executor for fingerprinting (CPU work off the event loop)
     */
    ReleaseGuard(final Repository repository, final Executor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    /**
     * Check an upload.
     *
     * @param artifact Storage key the upload would be written to
     * @param pkg Package name ({@code vendor/package})
     * @param version Resolved (sanitised) version
     * @param zip True for ZIP, false for TAR.GZ
     * @param upload Uploaded bytes
     * @return Verdict
     */
    CompletableFuture<Verdict> check(
        final Key artifact,
        final String pkg,
        final String version,
        final boolean zip,
        final byte[] upload
    ) {
        if (ReleaseGuard.mutable(version)) {
            return CompletableFuture.completedFuture(Verdict.NEW);
        }
        return this.published(pkg, version).thenCompose(
            listed -> this.repository.exists(artifact).thenCompose(exists -> {
                if (!exists) {
                    // Listed under another archive (e.g. .tar.gz vs .zip):
                    // a second, different archive for the same release.
                    return CompletableFuture.completedFuture(
                        listed ? Verdict.CONFLICT : Verdict.NEW
                    );
                }
                return this.compare(artifact, zip, version, upload).thenApply(
                    same -> {
                        if (!same) {
                            return Verdict.CONFLICT;
                        }
                        // Same content: done, unless an earlier upload never
                        // reached the metadata (then store it again to repair).
                        return listed ? Verdict.IDENTICAL : Verdict.NEW;
                    }
                );
            })
        );
    }

    /**
     * Whether the stored archive has the same content as the upload.
     *
     * <p>Content that cannot be verified counts as different: a stored
     * archive larger than {@link #MAX_STORED} is not read at all, and an
     * archive that is corrupt or past the {@link ArchiveFingerprint} limits
     * has no fingerprint. Either way the published release is kept and the
     * upload is a conflict. Fingerprinting inflates and hashes, so it runs
     * on the storage write pool, never on the calling (event-loop) thread.</p>
     *
     * @param artifact Stored archive key
     * @param zip True for ZIP
     * @param version Resolved version
     * @param upload Uploaded bytes
     * @return True when both have the same fingerprint
     */
    private CompletableFuture<Boolean> compare(
        final Key artifact, final boolean zip, final String version, final byte[] upload
    ) {
        return this.repository.storage().metadata(artifact).thenCompose(meta -> {
            final long size = meta.read(Meta.OP_SIZE).map(Long::longValue).orElse(-1L);
            if (size < 0 || size > ReleaseGuard.MAX_STORED) {
                return CompletableFuture.completedFuture(false);
            }
            return this.repository.value(artifact)
                .thenCompose(content -> content.asBytesFuture())
                .thenApplyAsync(stored -> {
                    final ArchiveFingerprint print = new ArchiveFingerprint(zip, version);
                    final Optional<String> mine = print.of(upload);
                    return mine.isPresent() && mine.equals(print.of(stored));
                }, this.executor);
        });
    }

    /**
     * Check a JSON package registration (no archive: the entry itself is
     * what gets published).
     *
     * @param pkg Package name ({@code vendor/package})
     * @param version Version
     * @param entry Package JSON being registered
     * @return Verdict
     */
    CompletableFuture<Verdict> checkEntry(
        final String pkg, final String version, final JsonObject entry
    ) {
        if (ReleaseGuard.mutable(version)) {
            return CompletableFuture.completedFuture(Verdict.NEW);
        }
        return this.repository.packages(new Name(pkg)).toCompletableFuture().thenCompose(
            found -> found
                .map(packages -> ReleaseGuard.entry(packages, pkg, version))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
        ).thenApply(existing -> {
            if (existing.isEmpty()) {
                return Verdict.NEW;
            }
            if (ReleaseGuard.normalised(existing.get(), version)
                .equals(ReleaseGuard.normalised(entry, version))) {
                return Verdict.IDENTICAL;
            }
            return Verdict.CONFLICT;
        });
    }

    /**
     * A version entry as the repository publishes it, minus the generated
     * {@code uid}: a registration whose version came from the query string
     * ({@code PUT /?version=1.0.0}) has no {@code version} field, while a
     * writer may store one, so both sides carry the resolved version.
     *
     * @param entry Entry
     * @param version Resolved version
     * @return Comparable entry
     */
    private static JsonObject normalised(final JsonObject entry, final String version) {
        final JsonObjectBuilder builder = Json.createObjectBuilder(entry).remove("uid");
        if (!entry.containsKey(JsonPackage.VRSN)) {
            builder.add(JsonPackage.VRSN, version);
        }
        return builder.build();
    }

    /**
     * The published entry of a version, if any.
     *
     * @param packages Packages file
     * @param pkg Package name
     * @param version Version
     * @return Entry
     */
    private static CompletableFuture<Optional<JsonObject>> entry(
        final Packages packages, final String pkg, final String version
    ) {
        return packages.content().toCompletableFuture()
            .thenCompose(content -> content.asJsonObjectFuture())
            .thenApply(json -> Optional.ofNullable(json.getJsonObject("packages"))
                .map(all -> all.get(pkg))
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .map(versions -> versions.get(version))
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast));
    }

    /**
     * Whether a version names a moving branch.
     *
     * @param version Version
     * @return True for {@code dev-*} and {@code *-dev}
     */
    private static boolean mutable(final String version) {
        final String lower = version.toLowerCase(Locale.ROOT);
        return lower.startsWith("dev-") || lower.endsWith("-dev");
    }

    /**
     * Whether the package metadata already lists the version.
     *
     * @param pkg Package name
     * @param version Version
     * @return True when listed
     */
    private CompletableFuture<Boolean> published(final String pkg, final String version) {
        return this.repository.packages(new Name(pkg)).toCompletableFuture().thenCompose(
            found -> found
                .map(packages -> ReleaseGuard.lists(packages, pkg, version))
                .orElseGet(() -> CompletableFuture.completedFuture(false))
        );
    }

    /**
     * Whether a packages file lists a version of a package.
     *
     * @param packages Packages file
     * @param pkg Package name
     * @param version Version
     * @return True when listed
     */
    private static CompletableFuture<Boolean> lists(
        final Packages packages, final String pkg, final String version
    ) {
        return packages.content().toCompletableFuture()
            .thenCompose(content -> content.asJsonObjectFuture())
            .thenApply(json -> Optional.ofNullable(json.getJsonObject("packages"))
                .map(all -> all.get(pkg))
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .map(versions -> versions.containsKey(version))
                .orElse(false));
    }
}
