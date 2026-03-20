/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Provides repository specific data required to evaluate cooldown decisions.
 */
public interface CooldownInspector {

    /**
     * Resolve release timestamp for artifact version if available.
     *
     * @param artifact Artifact identifier
     * @param version Artifact version
     * @return Future with optional release timestamp
     */
    CompletableFuture<Optional<Instant>> releaseDate(String artifact, String version);

    /**
     * Resolve dependencies for the artifact version.
     *
     * @param artifact Artifact identifier
     * @param version Artifact version
     * @return Future with dependencies
     */
    CompletableFuture<List<CooldownDependency>> dependencies(String artifact, String version);

    /**
     * Batch resolve release timestamps for dependency coordinates.
     * Default implementation parallelizes single-item {@link #releaseDate(String, String)} calls.
     * Implementations may override for efficiency.
     *
     * @param deps Dependency coordinates
     * @return Future with a map of dependency -> optional release timestamp
     */
    default CompletableFuture<Map<CooldownDependency, Optional<Instant>>> releaseDatesBatch(
        final Collection<CooldownDependency> deps
    ) {
        if (deps == null || deps.isEmpty()) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyMap());
        }
        final List<CompletableFuture<Map.Entry<CooldownDependency, Optional<Instant>>>> futures =
            deps.stream()
                .map(dep -> this.releaseDate(dep.artifact(), dep.version())
                    .thenApply(ts -> Map.entry(dep, ts)))
                .collect(Collectors.toList());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
}
