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
package com.auto1.pantera.asto.blob;

/**
 * Resolved per-repo WS1.7 (spec {@code WS1-storage-for-scale.md} &sect;3.B2)
 * download policy: the {@link DownloadMode} plus the presigned-URL validity
 * window. Carried from {@code RepoConfig} (pantera-main) down to whichever
 * per-format serving code opts into redirect eligibility (docker-adapter's
 * {@code Docker#downloadPolicy()} is the first wired case) without either
 * side depending on the other's module.
 *
 * @param mode Byte-serving strategy.
 * @param presignTtlSeconds Presigned URL validity window, in seconds. Only
 *  consulted when {@code mode != STREAM}; must be positive regardless (a
 *  {@link #streamOnly()} default still carries a valid, if unused, value).
 * @since 2.3.0
 */
public record DownloadPolicy(DownloadMode mode, long presignTtlSeconds) {

    /**
     * Default presigned-URL TTL when a repo does not configure one: 10
     * minutes, the midpoint of the spec's recommended 5-15 minute window.
     */
    public static final long DEFAULT_PRESIGN_TTL_SECONDS = 600L;

    public DownloadPolicy {
        if (presignTtlSeconds <= 0) {
            throw new IllegalArgumentException(
                "presignTtlSeconds must be positive, got " + presignTtlSeconds
            );
        }
    }

    /**
     * The safe default: always stream, never attempt a redirect. Used by
     * every {@code Docker}/route implementation that has not (yet) been
     * wired to read a repo's configured download-mode -- byte-identical to
     * pre-WS1.7 behaviour.
     *
     * @return Stream-only policy.
     */
    public static DownloadPolicy streamOnly() {
        return new DownloadPolicy(DownloadMode.STREAM, DEFAULT_PRESIGN_TTL_SECONDS);
    }
}
