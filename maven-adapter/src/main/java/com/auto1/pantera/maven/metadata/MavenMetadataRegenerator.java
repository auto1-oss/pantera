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
package com.auto1.pantera.maven.metadata;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.maven.asto.RepositoryChecksums;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.xembly.Directives;

/**
 * Server-side, concurrency-safe {@code maven-metadata.xml} regeneration
 * (WS4-maven.4 — the tentpole).
 *
 * <p>On hosted deploy, the client-uploaded {@code maven-metadata.xml} is no
 * longer trusted as the source of {@code <versions>} — the actual set of
 * version directories under the GA in storage is. This closes the
 * concurrent/stale-deploy hole: two clients publishing {@code 1.0} and
 * {@code 1.1} at the same time each historically PUT a metadata file
 * listing only their own version; last-write-wins silently dropped the
 * other version from {@code <versions>} even though its jar was present.
 *
 * <p>Runs the whole read-list-write cycle under a per-GA
 * {@link Storage#exclusively(Key, java.util.function.Function)} lock keyed
 * on the {@code maven-metadata.xml} key itself, so concurrent regenerations
 * for the same GA serialise instead of racing. This is the same algorithm
 * {@code pantera-main}'s importer {@code MetadataRegenerator.regenerateMaven}
 * has run against production traffic; it now lives here (maven-adapter) so
 * the hosted deploy path ({@code UploadSlice}, which cannot depend on
 * pantera-main) can call it too — the importer delegates to this class
 * rather than keeping a second copy of the algorithm.
 *
 * <p>Scope: the GA-level {@code maven-metadata.xml} (the {@code <versions>}
 * listing) only. Snapshot-level {@code <snapshot><timestamp>} /
 * {@code <snapshotVersions>} maintenance stays client-driven — out of
 * scope per WS4-maven.md §5.
 *
 * @since 2.3.0
 */
public final class MavenMetadataRegenerator {

    /**
     * Maximum lock-acquisition retries before giving up. The underlying
     * {@code StorageLock} is a test-and-set "proposal" lock with no retry
     * of its own — under a genuine burst of concurrent regenerations for
     * the same GA, several contenders can lose the race simultaneously.
     * A bounded retry here is what actually delivers the "zero lost
     * versions under a concurrent burst" guarantee: each retry re-lists
     * storage from scratch, so a later winner still picks up every
     * already-committed primary, including ones from contenders that lost
     * an earlier round.
     */
    private static final int MAX_LOCK_RETRIES = 20;

    /**
     * Base retry backoff (doubles each attempt up to {@link #RETRY_MAX_DELAY_MS}).
     * Capped-exponential, worst case ~8 s total across 20 retries — far
     * below any client HTTP timeout (a {@code mvn deploy} client default is
     * 60s+), unlike the importer's bulk-path ceiling which explicitly opted
     * OUT of retrying this same lock to avoid 504s under thousands of
     * queued imports. A single {@code mvn deploy} burst has no such volume.
     */
    private static final long RETRY_BASE_DELAY_MS = 10L;

    /** Cap on the per-attempt backoff delay — see {@link #RETRY_BASE_DELAY_MS}. */
    private static final long RETRY_MAX_DELAY_MS = 500L;

    /**
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Ctor.
     *
     * @param storage Repository storage
     */
    public MavenMetadataRegenerator(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Regenerate {@code maven-metadata.xml} for a group/artifact coordinate,
     * re-deriving the version set from what's actually in storage under
     * {@code baseKey}, under an exclusive per-GA lock. Retries a bounded
     * number of times on lock-acquisition contention (see
     * {@link #MAX_LOCK_RETRIES}) — the last retry to run is guaranteed to
     * see every primary saved before it started, since each attempt fully
     * re-lists storage rather than merging a stale in-memory view.
     *
     * @param baseKey Base key: {@code groupId-path/artifactId}
     * @param groupId Maven group id (dotted)
     * @param artifactId Maven artifact id
     * @param currentVersion The version just deployed — always included even
     *                       if the version directory listing hasn't
     *                       propagated yet on eventually-consistent storage
     * @return Completion stage, resolved once the metadata (and its
     *         checksum sidecars) have been written
     */
    public CompletionStage<Void> regenerate(
        final Key baseKey,
        final String groupId,
        final String artifactId,
        final String currentVersion
    ) {
        return this.regenerateAttempt(baseKey, groupId, artifactId, currentVersion, 0);
    }

    /**
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletionStage<Void> regenerateAttempt(
        final Key baseKey, final String groupId, final String artifactId,
        final String currentVersion, final int attempt
    ) {
        final Key metadataKey = new Key.From(baseKey, "maven-metadata.xml");
        final CompletableFuture<Void> attemptFuture = this.storage.exclusively(
            metadataKey,
            locked -> this.storage.list(baseKey)
                .exceptionally(ex -> List.of())
                .thenApply(keys -> collectVersions(baseKey, currentVersion, keys))
                .thenCompose(versions -> this.writeMetadata(baseKey, groupId, artifactId, versions))
        ).toCompletableFuture();
        return attemptFuture.handle((ignored, err) -> err).thenCompose(err -> {
            if (err == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (attempt >= MAX_LOCK_RETRIES) {
                return CompletableFuture.<Void>failedFuture(err);
            }
            final long cap = Math.min(
                RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS * (1L << Math.min(attempt, 20))
            );
            // Full jitter over [1, cap]. A burst of same-GA regenerations
            // rendezvouses and loses the proposal lock in lockstep; a
            // jitter-less exponential backoff then keeps every contender
            // colliding on every retry round, so a fraction of concurrent
            // bursts exhaust the whole retry budget with ZERO winners -- and
            // then maven-metadata.xml is never written and versions are lost,
            // which is exactly what this retry exists to prevent. Randomizing
            // the entire delay desynchronizes the contenders so one eventually
            // retries alone, wins the proposal lock, and (re-listing storage)
            // captures every already-committed version.
            final long delay = ThreadLocalRandom.current().nextLong(1L, cap + 1L);
            return CompletableFuture.supplyAsync(
                () -> null,
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
            ).thenCompose(
                ignored2 -> this.regenerateAttempt(baseKey, groupId, artifactId, currentVersion, attempt + 1)
            );
        });
    }

    /**
     * Collect the set of published versions from the keys listed under the
     * GA base key: every first-level directory segment that parses as a
     * {@link Version}, excluding {@code maven-metadata.xml} itself, hidden
     * entries, and lock/temp artifacts.
     *
     * @param baseKey Base key containing version directories
     * @param currentVersion Version being deployed right now — always
     *                       included so a fresh GA's first deploy is never
     *                       dropped by a storage listing that hasn't caught
     *                       up yet
     * @param keys All keys returned by {@code storage.list(baseKey)}
     * @return Version-ordered set of published versions
     */
    private static TreeSet<String> collectVersions(
        final Key baseKey, final String currentVersion, final Collection<Key> keys
    ) {
        final TreeSet<String> versions = new TreeSet<>(
            (left, right) -> new Version(left).compareTo(new Version(right))
        );
        versions.add(currentVersion);
        final String prefix = baseKey.string();
        final String normalizedPrefix = prefix.isEmpty() ? "" : prefix + "/";
        for (final Key key : keys) {
            final String relative = key.string().substring(
                Math.min(normalizedPrefix.length(), key.string().length())
            );
            if (relative.isEmpty()) {
                continue;
            }
            final String firstSegment = relative.split("/")[0];
            if ("maven-metadata.xml".equals(firstSegment)
                || firstSegment.endsWith(".lastUpdated")
                || firstSegment.endsWith(".properties")
                || firstSegment.startsWith(".")
                || firstSegment.contains(".tmp")
                || firstSegment.contains(".lock")) {
                continue;
            }
            try {
                new Version(firstSegment);
                versions.add(firstSegment);
            } catch (final IllegalArgumentException ex) {
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Skipping non-version directory during metadata regeneration")
                    .eventCategory("file")
                    .eventAction("maven_metadata_regenerate")
                    .field("file.directory", firstSegment)
                    .field("log.source", "application")
                    .log();
            }
        }
        return versions;
    }

    /**
     * Build and persist {@code maven-metadata.xml} via {@link MavenMetadata},
     * which already derives {@code <latest>} (highest of all versions) and
     * {@code <release>} (highest non-SNAPSHOT version) from the version set —
     * so a stale client-sent {@code <release>} can never disagree with the
     * server-recomputed {@code <latest>}. Regenerates the checksum sidecars
     * over the freshly-written bytes, inside the same lock, so metadata and
     * its checksums are always consistent.
     *
     * @param baseKey Base key
     * @param groupId Group id
     * @param artifactId Artifact id
     * @param versions Version set to publish
     * @return Completion stage
     */
    private CompletionStage<Void> writeMetadata(
        final Key baseKey, final String groupId, final String artifactId, final TreeSet<String> versions
    ) {
        if (versions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final Directives base = new Directives()
            .add("metadata")
                .add("groupId").set(groupId).up()
                .add("artifactId").set(artifactId).up()
            .up();
        final MavenMetadata metadata = new MavenMetadata(base).versions(versions);
        return metadata.save(this.storage, baseKey).thenCompose(
            metadataKey -> new RepositoryChecksums(this.storage).generate(metadataKey)
                .exceptionally(MavenMetadataRegenerator::ignoreConcurrentReplace)
        );
    }

    /**
     * Tolerate a concurrent-burst replacement of {@code maven-metadata.xml}
     * during the checksum read-back. {@link Storage#exclusively}'s {@code
     * StorageLock} is a best-effort proposal lock, not a true mutex, so under
     * a concurrent-deploy burst a peer regeneration can replace the file
     * between our {@code save()} above and reading it back to checksum it. The
     * regeneration that ultimately wins the burst writes its own consistent
     * checksums over its own bytes, so a superseded attempt's checksum step is
     * correctly a no-op: swallowing the not-found shape here keeps the burst
     * converging instead of exhausting the retry budget on a benign race
     * (previously this surfaced as {@code ValueNotFoundException} propagating
     * out of the regenerator under load). Any other failure is re-propagated
     * so the {@link #regenerateAttempt} retry loop can act on it.
     *
     * @param err Throwable from checksum generation.
     * @return {@code null} when the metadata was concurrently replaced.
     */
    private static Void ignoreConcurrentReplace(final Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof com.auto1.pantera.asto.ValueNotFoundException) {
                return null;
            }
            cause = cause.getCause();
        }
        throw new java.util.concurrent.CompletionException(err);
    }
}
