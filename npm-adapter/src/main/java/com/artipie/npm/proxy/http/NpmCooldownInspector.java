/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.http;

import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.npm.proxy.NpmRemote;
import com.artipie.npm.proxy.model.NpmPackage;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Maybe;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * NPM cooldown inspector with bounded cache to prevent memory leaks.
 * Uses Caffeine cache with automatic eviction to limit Old Gen growth.
 */
final class NpmCooldownInspector implements CooldownInspector,
    com.artipie.cooldown.InspectorRegistry.InvalidatableInspector {

    private final NpmRemote remote;
    
    /**
     * Bounded cache of package metadata.
     * Max 10,000 packages, expire after 24 hours.
     * Each entry is ~10-50KB, so max ~500MB memory usage.
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, CompletableFuture<Optional<JsonObject>>> metadata;

    NpmCooldownInspector(final NpmRemote remote) {
        this.remote = remote;
        this.metadata = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(10_000)  // Limit memory usage
            .expireAfterWrite(Duration.ofHours(24))  // Auto-evict old entries
            .recordStats()  // Enable metrics
            .build();
    }

    @Override
    public void invalidate(final String artifact, final String version) {
        this.metadata.invalidate(artifact);
    }

    @Override
    public void clearAll() {
        this.metadata.invalidateAll();
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(
        final String artifact,
        final String version
    ) {
        return this.metadata(artifact).thenApply(
            meta -> meta.flatMap(json -> {
                final JsonObject times = json.getJsonObject("time");
                if (times == null) {
                    return Optional.empty();
                }
                final String value = times.getString(version, null);
                if (value == null) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(Instant.parse(value));
                } catch (final Exception ignored) {
                    return Optional.empty();
                }
            })
        );
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(
        final String artifact,
        final String version
    ) {
        return this.metadata(artifact).thenCompose(meta -> {
            if (meta.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            final JsonObject versions = meta.get().getJsonObject("versions");
            if (versions == null) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            final JsonObject details = versions.getJsonObject(version);
            if (details == null) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            final List<CompletableFuture<Optional<CooldownDependency>>> futures = new ArrayList<>();
            futures.addAll(createDependencyFutures(details.getJsonObject("dependencies")));
            futures.addAll(createDependencyFutures(details.getJsonObject("optionalDependencies")));
            if (futures.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(
                ignored -> futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(Optional::stream)
                    .collect(Collectors.toList())
            );
        });
    }

    private Collection<CompletableFuture<Optional<CooldownDependency>>> createDependencyFutures(
        final JsonObject deps
    ) {
        if (deps == null || deps.isEmpty()) {
            return Collections.emptyList();
        }
        final List<CompletableFuture<Optional<CooldownDependency>>> list = new ArrayList<>(deps.size());
        for (final String key : deps.keySet()) {
            final String spec = deps.getString(key, "").trim();
            if (spec.isEmpty()) {
                continue;
            }
            list.add(this.resolveDependency(key, spec));
        }
        return list;
    }

    private CompletableFuture<Optional<CooldownDependency>> resolveDependency(
        final String name,
        final String range
    ) {
        return this.metadata(name).thenApply(meta -> meta.flatMap(json -> {
                final JsonObject versions = json.getJsonObject("versions");
                if (versions == null || versions.isEmpty()) {
                    return Optional.empty();
                }
                Semver best = null;
                for (final String candidate : versions.keySet()) {
                    try {
                        final Semver semver = new Semver(candidate, Semver.SemverType.NPM);
                        if (semver.satisfies(range)) {
                            if (best == null || semver.isGreaterThan(best)) {
                                best = semver;
                            }
                        }
                    } catch (final SemverException ignored) {
                        // ignore unparsable versions
                    }
                }
                if (best != null) {
                    return Optional.of(new CooldownDependency(name, best.getValue()));
                }
                // Range could be exact version string
                if (versions.containsKey(range)) {
                    return Optional.of(new CooldownDependency(name, range));
                }
                return Optional.empty();
            })
        );
    }

    private synchronized CompletableFuture<Optional<JsonObject>> metadata(final String name) {
        // Check cache first (inside synchronized to prevent race)
        final CompletableFuture<Optional<JsonObject>> cached = this.metadata.getIfPresent(name);
        if (cached != null) {
            return cached;
        }
        
        // Create future and cache it immediately to prevent duplicate loads
        final CompletableFuture<Optional<JsonObject>> future = this.loadPackage(name)
            .thenApply(optional -> optional.map(pkg ->
                Json.createReader(new StringReader(pkg.content())).readObject()
            ));
        
        // Cache the future immediately (even if it might fail)
        // This prevents duplicate concurrent loads for the same package
        this.metadata.put(name, future);
        
        // Remove from cache if load fails or returns empty
        future.thenAccept(result -> {
            if (result.isEmpty()) {
                this.metadata.invalidate(name);
            }
        });
        
        return future;
    }

    private CompletableFuture<Optional<NpmPackage>> loadPackage(final String name) {
        final Maybe<NpmPackage> maybe = this.remote.loadPackage(name);
        if (maybe == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return maybe
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .toSingle()
            .to(SingleInterop.get())
            .toCompletableFuture();
    }
}
