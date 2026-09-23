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
import com.auto1.pantera.composer.Name;
import com.auto1.pantera.composer.Packages;
import com.auto1.pantera.composer.Repository;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.JsonObject;

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
     * Repository.
     */
    private final Repository repository;

    /**
     * Ctor.
     *
     * @param repository Repository
     */
    ReleaseGuard(final Repository repository) {
        this.repository = repository;
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
                return this.repository.value(artifact)
                    .thenCompose(content -> content.asBytesFuture())
                    .thenApply(stored -> {
                        final ArchiveFingerprint print = new ArchiveFingerprint(zip, version);
                        if (!print.of(stored).equals(print.of(upload))) {
                            return Verdict.CONFLICT;
                        }
                        // Same content: done, unless an earlier upload never
                        // reached the metadata (then store it again to repair).
                        return listed ? Verdict.IDENTICAL : Verdict.NEW;
                    });
            })
        );
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
            if (ReleaseGuard.withoutUid(existing.get()).equals(ReleaseGuard.withoutUid(entry))) {
                return Verdict.IDENTICAL;
            }
            return Verdict.CONFLICT;
        });
    }

    /**
     * A version entry without the generated {@code uid}.
     *
     * @param entry Entry
     * @return Entry without uid
     */
    private static JsonObject withoutUid(final JsonObject entry) {
        return javax.json.Json.createObjectBuilder(entry).remove("uid").build();
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
