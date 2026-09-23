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
package com.auto1.pantera.docker.cooldown;

import com.auto1.pantera.http.Headers;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Makes sure the release date of a manifest served by the upstream is known
 * to the cooldown inspector before the manifest is evaluated.
 *
 * <p>The cache layer records release dates asynchronously, after the
 * manifest has already been handed back, so the first evaluation of a fresh
 * tag used to find no date, allow the tag and cache that "allowed" decision.
 * The recorder closes that window by resolving the date synchronously when
 * the inspector does not have it yet.</p>
 *
 * @since 2.2.9
 */
@FunctionalInterface
public interface ManifestReleaseRecorder {

    /**
     * Recorder that records nothing (release dates come from elsewhere).
     */
    ManifestReleaseRecorder NONE = (name, artifact, reference, digest, headers, manifest) ->
        CompletableFuture.completedFuture(null);

    /**
     * Record the release date of a served manifest, if not known yet.
     *
     * @param name Image name as in the request path (for upstream/storage access)
     * @param artifact Canonical cooldown artifact name
     * @param reference Tag or digest the manifest was requested by
     * @param digest Manifest digest from {@code Docker-Content-Digest}, if any
     * @param headers Upstream response headers
     * @param manifest Manifest bytes
     * @return Completion; never fails the caller's request
     */
    CompletableFuture<Void> record(
        String name, String artifact, String reference, Optional<String> digest,
        Headers headers, byte[] manifest
    );
}
