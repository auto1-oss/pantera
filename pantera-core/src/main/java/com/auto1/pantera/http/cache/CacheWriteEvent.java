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
 * <p>{@code bytesOnDisk} points at the local file from which the cache
 * was just populated. The file is deleted shortly after this event is
 * fired (in the sync path the writer cleans up the temp file once it
 * has streamed into storage; in the verified-artifact path the response
 * Flowable disposes the temp file once consumed). Consumers that need
 * the bytes should either copy them eagerly or read them lazily from
 * the {@link com.auto1.pantera.asto.Storage} via the cached key.
 *
 * @param repoName    The repository name (e.g. "maven_proxy"). Carried
 *                    so consumers can route per-repo without a back-
 *                    reference to the writer.
 * @param urlPath     The URL path of the artifact (e.g.
 *                    "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom").
 * @param bytesOnDisk Filesystem path of the freshly-written cache file
 *                    (the source temp file in the sync path; the
 *                    {@link ProxyCacheWriter.VerifiedArtifact#tempFile()}
 *                    in the verified-artifact path).
 * @param sizeBytes   Size in bytes of the primary artifact.
 * @param writtenAt   When the write completed.
 *
 * @since 2.2.0
 */
public record CacheWriteEvent(
    String repoName,
    String urlPath,
    Path bytesOnDisk,
    long sizeBytes,
    Instant writtenAt
) {}
