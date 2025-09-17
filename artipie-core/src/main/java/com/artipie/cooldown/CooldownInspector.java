/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
}
