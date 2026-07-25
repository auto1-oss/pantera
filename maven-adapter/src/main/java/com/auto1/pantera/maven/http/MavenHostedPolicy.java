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
 * @param releaseImmutable Reject redeploy of an existing non-SNAPSHOT
 *                          primary with 409 instead of overwriting it
 *                          (WS4-maven.6). Default {@code false}. SNAPSHOT
 *                          redeploys are always allowed regardless.
 * @since 2.3.0
 */
public record MavenHostedPolicy(boolean verifyPgp, boolean releaseImmutable) {

    /**
     * Legacy/default policy — byte-identical to pre-2.3.0 hosted-write
     * behaviour: no signature verification, unconditional overwrite.
     */
    public static final MavenHostedPolicy DEFAULT = new MavenHostedPolicy(false, false);
}
