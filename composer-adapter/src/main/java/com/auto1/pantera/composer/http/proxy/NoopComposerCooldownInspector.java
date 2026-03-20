/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.cooldown.CooldownDependency;
import com.auto1.pantera.cooldown.CooldownInspector;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * No-op Composer cooldown inspector.
 * Always returns empty results.
 */
final class NoopComposerCooldownInspector implements CooldownInspector {

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(
        final String artifact,
        final String version
    ) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(
        final String artifact,
        final String version
    ) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
