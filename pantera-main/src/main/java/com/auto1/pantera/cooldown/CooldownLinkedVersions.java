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
package com.auto1.pantera.cooldown;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-repository resolvers of the cooldown versions a manual unblock must
 * release together with the named one.
 *
 * <p>Docker is the case this exists for: pulling {@code image:tag} is
 * evaluated under the tag AND the manifest digest the tag resolves to, and a
 * multi-arch tag is then completed by digest-addressed pulls of its child
 * manifests — each its own cooldown row. Releasing only the tag row left the
 * digest rows in force, so the pull kept failing after the unblock. The
 * docker proxy registers a resolver that reads the tag's cached manifest and
 * answers exactly the digests reachable from it (manifest digest plus, for
 * an index, its children). An unrelated digest of the same image is never
 * returned, so it stays blocked.</p>
 *
 * <p>Repositories are registered when their slice is built (every configured
 * repository is built at startup), so every instance can expand an unblock
 * no matter which instance served the pull.</p>
 *
 * @since 2.2.9
 */
public final class CooldownLinkedVersions {

    /**
     * Resolver answering nothing (no linked versions).
     */
    public static final Resolver NONE = (artifact, version) ->
        CompletableFuture.completedFuture(List.of());

    /**
     * Process-wide registry.
     */
    private static final CooldownLinkedVersions INSTANCE = new CooldownLinkedVersions();

    /**
     * Resolvers by repository name.
     */
    private final Map<String, Resolver> resolvers = new ConcurrentHashMap<>();

    private CooldownLinkedVersions() {
    }

    /**
     * Process-wide registry.
     *
     * @return Registry
     */
    public static CooldownLinkedVersions instance() {
        return INSTANCE;
    }

    /**
     * Register (or replace, on repository reload) a repository's resolver.
     *
     * @param repoName Repository name
     * @param resolver Resolver
     */
    public void register(final String repoName, final Resolver resolver) {
        this.resolvers.put(repoName, resolver);
    }

    /**
     * Resolver for a repository.
     *
     * @param repoName Repository name
     * @return Registered resolver, or {@link #NONE}
     */
    public Resolver forRepo(final String repoName) {
        return this.resolvers.getOrDefault(repoName, NONE);
    }

    /**
     * Resolves the versions linked to one cooldown version.
     */
    @FunctionalInterface
    public interface Resolver {

        /**
         * Versions released together with {@code version} of {@code artifact}.
         *
         * @param artifact Cooldown artifact name
         * @param version Released version
         * @return Linked versions (never including {@code version} itself)
         */
        CompletableFuture<List<String>> linked(String artifact, String version);
    }
}
