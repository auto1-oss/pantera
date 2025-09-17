/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi.http;

import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class PyProxyCooldownInspector implements CooldownInspector {

    private final ConcurrentMap<String, Instant> releases;

    PyProxyCooldownInspector() {
        this.releases = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Optional.ofNullable(this.releases.get(key(artifact, version))));
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    void register(final String artifact, final String version, final Instant release) {
        this.releases.put(key(artifact, version), release);
    }

    boolean known(final String artifact, final String version) {
        return this.releases.containsKey(key(artifact, version));
    }

    private static String key(final String artifact, final String version) {
        return String.format("%s:%s", artifact, version);
    }
}
