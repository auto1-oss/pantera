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

import java.nio.file.Path;
import java.time.Instant;

/**
 * Event fired by {@link ProxyCacheWriter} after a successful proxy cache
 * write. Carries the minimum information a downstream consumer needs to
 * decide whether to act on the cached artifact (e.g., parse the bytes,
 * warm the dependency cache, fire metrics).
 *
 * <p>Consumers MUST NOT block on the cache-write path. Use a bounded
 * queue or hand work off to a background worker: the writer catches and
 * logs any throwable from the callback, but slow callbacks would still
 * stall the response if they ran inline.
 *
 * <p>The callback may be invoked concurrently from any thread that
 * completes a write (typically a {@code ForkJoinPool.commonPool} worker).
 * Consumers MUST be thread-safe.
 *
 * <p>{@code bytesOnDisk} points at a file holding the freshly-cached
 * bytes. The {@code callerOwnsSnapshot} flag tells the consumer who
 * controls that file's lifetime:</p>
 * <ul>
 *   <li>{@code callerOwnsSnapshot = true} (default, {@link ProxyCacheWriter}
 *       path) — the writer owns a temp file and will delete it as soon as
 *       this callback returns. A consumer that wants to use the bytes
 *       asynchronously MUST snapshot them before going async (e.g. the
 *       {@link com.auto1.pantera.prefetch.PrefetchDispatcher} copies the
 *       file to a dispatcher-owned temp file).</li>
 *   <li>{@code callerOwnsSnapshot = false} ({@code NpmCacheWriteBridge}
 *       zero-copy passthrough path) — {@code bytesOnDisk} is the actual
 *       on-disk path managed by {@link com.auto1.pantera.asto.Storage}
 *       (typically {@code FileStorage}). The storage owns lifetime and
 *       may evict the file at any time. Consumers MUST treat the path
 *       as read-only and MUST NOT delete it. Async readers must handle
 *       {@link java.nio.file.NoSuchFileException} gracefully (storage
 *       evicted the file mid-read).</li>
 * </ul>
 *
 * @param repoName            The repository name (e.g. "maven_proxy").
 *                            Carried so consumers can route per-repo
 *                            without a back-reference to the writer.
 * @param urlPath             The URL path of the artifact (e.g.
 *                            "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom").
 * @param bytesOnDisk         Filesystem path of a file containing the
 *                            freshly-cached bytes. See
 *                            {@code callerOwnsSnapshot} for lifetime
 *                            semantics.
 * @param sizeBytes           Size in bytes of the primary artifact.
 * @param writtenAt           When the write completed.
 * @param callerOwnsSnapshot  {@code true} when {@code bytesOnDisk} is
 *                            a caller-owned temp file deleted right
 *                            after the callback returns; {@code false}
 *                            when {@code bytesOnDisk} is a storage-
 *                            owned path (zero-copy passthrough) whose
 *                            lifetime extends until storage evicts it.
 *
 * @since 2.2.0
 */
public record CacheWriteEvent(
    String repoName,
    String urlPath,
    Path bytesOnDisk,
    long sizeBytes,
    Instant writtenAt,
    boolean callerOwnsSnapshot
) {

    /**
     * Caller-owned compatibility constructor — equivalent to
     * {@code callerOwnsSnapshot = true}. Existing call sites
     * ({@link ProxyCacheWriter#commit}, {@code commitVerified}) continue
     * to use this 5-arg form unchanged.
     *
     * @param repoName    Repository name.
     * @param urlPath     URL path of the artifact.
     * @param bytesOnDisk Caller-owned temp file path.
     * @param sizeBytes   Size in bytes.
     * @param writtenAt   Write completion timestamp.
     */
    public CacheWriteEvent(
        final String repoName,
        final String urlPath,
        final Path bytesOnDisk,
        final long sizeBytes,
        final Instant writtenAt
    ) {
        this(repoName, urlPath, bytesOnDisk, sizeBytes, writtenAt, true);
    }
}
