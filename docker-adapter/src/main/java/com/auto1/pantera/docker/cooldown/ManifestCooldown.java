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

import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.log.EcsLogger;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The cooldown decision on a manifest of a Docker proxy: the reference it
 * was asked by and, for a tag, the digest the tag resolves to. Either one
 * in cooldown refuses the manifest (see {@link DockerManifestByTagHandler}
 * for why both are checked). A check that fails allows: cooldown fails
 * open.
 *
 * <p>The answer to a client's {@code GET} of a manifest by tag and the
 * copy of a manifest a {@code HEAD} read into the proxy's cache are decided
 * here, the same way.</p>
 *
 * @since 2.2.15
 */
public final class ManifestCooldown {

    /**
     * Cooldown evaluation service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector (release-date lookups).
     */
    private final CooldownInspector inspector;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Ctor.
     *
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public ManifestCooldown(
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final String repoType,
        final String repoName
    ) {
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.repoType = repoType;
        this.repoName = repoName;
    }

    /**
     * Why cooldown refuses a manifest.
     *
     * @param artifact Canonical cooldown artifact name of the image
     * @param reference Tag or digest the manifest was asked by
     * @param digest Digest of the manifest, when known; ignored when it is
     *  the reference itself
     * @param user Requesting user
     * @return {@code tag_blocked} or {@code digest_blocked}; empty when the
     *  manifest is allowed
     */
    public CompletableFuture<Optional<String>> refusal(
        final String artifact,
        final String reference,
        final Optional<String> digest,
        final String user
    ) {
        final CompletableFuture<Boolean> byref = this.blocked(artifact, reference, user);
        final CompletableFuture<Boolean> bydigest = digest
            .filter(value -> !value.equals(reference))
            .map(value -> this.blocked(artifact, value, user))
            .orElseGet(() -> CompletableFuture.completedFuture(false));
        return byref.thenCombine(
            bydigest,
            (refblocked, digestblocked) -> {
                final Optional<String> reason;
                if (refblocked) {
                    reason = Optional.of("tag_blocked");
                } else if (digestblocked) {
                    reason = Optional.of("digest_blocked");
                } else {
                    reason = Optional.empty();
                }
                return reason;
            }
        );
    }

    /**
     * Whether cooldown blocks one version; a failed evaluation allows it.
     *
     * @param artifact Canonical cooldown artifact name
     * @param version Tag or digest
     * @param user Requesting user
     * @return Whether blocked
     */
    private CompletableFuture<Boolean> blocked(
        final String artifact, final String version, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            artifact,
            version,
            user == null ? "docker-manifest" : user,
            Instant.now()
        );
        return this.cooldown.evaluate(req, this.inspector)
            .thenApply(result -> result.blocked())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.docker")
                    .message("Cooldown evaluation failed; treating as allowed")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return false;
            });
    }
}
