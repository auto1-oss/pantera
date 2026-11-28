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
package com.auto1.pantera.maven.http;

/**
 * Per-repo hosted-write policy flags for the Maven/Gradle {@code local}
 * mode (WS4-maven.2, .6): bundled into one record so
 * {@link MavenSlice}/{@link UploadSlice} constructors gain a single new
 * parameter instead of two, keeping PMD's parameter-count ceiling clear.
 *
 * @param verifyPgp Verify a primary's {@code .asc} signature against the
 *                  admin-managed keyring before acknowledging it
 *                  (WS4-maven.2). Default {@code false}.
 * @param immutable Reject redeploy of an existing non-SNAPSHOT primary with
 *                  409 instead of overwriting it (an identical re-upload is
 *                  an idempotent 201). Fed from the repository's
 *                  {@code immutable} setting (deprecated alias:
 *                  {@code releaseImmutable}). Default {@code true}; a
 *                  repository opts out with {@code immutable: false}.
 *                  SNAPSHOT redeploys are always allowed regardless.
 * @since 2.3.0
 */
public record MavenHostedPolicy(boolean verifyPgp, boolean immutable) {

    /**
     * Default policy: no PGP signature verification, and published release
     * versions are immutable (a repository opts out with {@code immutable: false}).
     */
    public static final MavenHostedPolicy DEFAULT = new MavenHostedPolicy(false, true);
}
