/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.cache;

import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DockerProxyCooldownInspector implements CooldownInspector {

    private final ConcurrentMap<String, Instant> releases;

    private final ConcurrentMap<String, String> digestOwners;

    private final ConcurrentMap<String, Boolean> seen;

    public DockerProxyCooldownInspector() {
        this.releases = new ConcurrentHashMap<>();
        this.digestOwners = new ConcurrentHashMap<>();
        this.seen = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Optional.ofNullable(this.releases.get(key(artifact, version))));
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    public void register(
        final String artifact,
        final String version,
        final Optional<Instant> release,
        final String owner,
        final String repoName,
        final Optional<String> digest
    ) {
        final String key = key(artifact, version);
        this.seen.putIfAbsent(key, Boolean.TRUE);
        release.ifPresent(value -> this.releases.put(key, value));
        this.digestOwners.put(digestKey(repoName, version), owner);
        digest.ifPresent(value -> this.digestOwners.put(digestKey(repoName, value), owner));
    }

    public void recordRelease(final String artifact, final String version, final Instant release) {
        this.seen.putIfAbsent(key(artifact, version), Boolean.TRUE);
        this.releases.put(key(artifact, version), release);
    }

    public Optional<String> ownerFor(final String repoName, final String digest) {
        return Optional.ofNullable(this.digestOwners.get(digestKey(repoName, digest)));
    }

    public boolean known(final String artifact, final String version) {
        return this.seen.containsKey(key(artifact, version));
    }

    public boolean isBlocked(final String artifact, final String digest) {
        return false;
    }

    private static String key(final String artifact, final String version) {
        return String.format("%s:%s", artifact, version);
    }

    private static String digestKey(final String repoName, final String digest) {
        return String.format("%s@%s", repoName, digest);
    }
}
