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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.manifest.ManifestLayer;
import com.auto1.pantera.docker.misc.ImageTag;
import com.auto1.pantera.docker.misc.JoinedTagsSource;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.scheduling.ArtifactEvent;
import org.slf4j.MDC;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Cache implementation of {@link Repo}.
 */
public final class CacheManifests implements Manifests {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "docker-proxy";

    /**
     * Repository (image) name.
     */
    private final String name;

    /**
     * Origin repository.
     */
    private final Repo origin;

    /**
     * Cache repository.
     */
    private final Repo cache;

    /**
     * Events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Pantera repository name.
     */
    private final String rname;

    /**
     * Cooldown inspector carrying request context.
     */
    private final Optional<DockerProxyCooldownInspector> inspector;

    /**
     * Upstream URL for metrics.
     */
    private final String upstreamUrl;

    /**
     * @param name Repository name.
     * @param origin Origin repository.
     * @param cache Cache repository.
     * @param events Artifact metadata events
     * @param registryName Pantera repository name
     */
    public CacheManifests(String name, Repo origin, Repo cache,
        Optional<Queue<ArtifactEvent>> events, String registryName,
        Optional<DockerProxyCooldownInspector> inspector) {
        this(name, origin, cache, events, registryName, inspector, "unknown");
    }

    /**
     * @param name Repository name.
     * @param origin Origin repository.
     * @param cache Cache repository.
     * @param events Artifact metadata events
     * @param registryName Pantera repository name
     * @param inspector Cooldown inspector
     * @param upstreamUrl Upstream URL for metrics
     */
    public CacheManifests(String name, Repo origin, Repo cache,
        Optional<Queue<ArtifactEvent>> events, String registryName,
        Optional<DockerProxyCooldownInspector> inspector, String upstreamUrl) {
        this.name = name;
        this.origin = origin;
        this.cache = cache;
        this.events = events;
        this.rname = registryName;
        this.inspector = inspector;
        this.upstreamUrl = upstreamUrl;
    }

    @Override
    public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
        final long startTime = System.currentTimeMillis();
        final String requestOwner = MDC.get("user.name");
        return this.origin.manifests().get(ref).handle(
            (original, throwable) -> {
                final long duration = System.currentTimeMillis() - startTime;
                final CompletionStage<Optional<Manifest>> result;
                if (throwable == null) {
                    if (original.isPresent()) {
                        this.recordProxyMetric("success", duration);
                        EcsLogger.info("com.auto1.pantera.docker.proxy")
                            .message("CacheManifests origin returned manifest")
                            .eventCategory("web")
                            .eventAction("cache_manifest_get")
                            .eventOutcome("success")
                            .field("repository.name", this.rname)
                            .field("container.image.name", this.name)
                            .field("container.image.hash.all", ref.digest())
                            .field("url.original", this.upstreamUrl)
                            .duration(duration)
                            .log();
                        Manifest manifest = original.get();
                        if (Manifest.MANIFEST_SCHEMA2.equals(manifest.mediaType()) ||
                            Manifest.MANIFEST_OCI_V1.equals(manifest.mediaType()) ||
                            Manifest.MANIFEST_LIST_SCHEMA2.equals(manifest.mediaType()) ||
                            Manifest.MANIFEST_OCI_INDEX.equals(manifest.mediaType())) {
                            this.copy(ref, requestOwner);
                            result = CompletableFuture.completedFuture(original);
                        } else {
                            EcsLogger.warn("com.auto1.pantera.docker")
                                .message("Cannot add manifest to cache")
                                .eventCategory("web")
                                .eventAction("manifest_cache")
                                .eventOutcome("failure")
                                .field("repository.name", this.rname)
                                .field("container.image.name", this.name)
                                .field("container.image.hash.all", ref.digest())
                                .field("file.type", manifest.mediaType())
                                .log();
                            result = CompletableFuture.completedFuture(original);
                        }
                    } else {
                        this.recordProxyMetric("not_found", duration);
                        EcsLogger.info("com.auto1.pantera.docker.proxy")
                            .message("CacheManifests origin returned empty, falling back to cache")
                            .eventCategory("web")
                            .eventAction("cache_manifest_get")
                            .eventOutcome("failure")
                            .field("event.reason", "artifact_not_found")
                            .field("repository.name", this.rname)
                            .field("container.image.name", this.name)
                            .field("container.image.hash.all", ref.digest())
                            .field("url.original", this.upstreamUrl)
                            .duration(duration)
                            .log();
                        result = this.cache.manifests().get(ref).exceptionally(ignored -> original);
                    }
                } else {
                    this.recordProxyMetric("exception", duration);
                    this.recordUpstreamErrorMetric(throwable);
                    EcsLogger.error("com.auto1.pantera.docker")
                        .message("Failed getting manifest")
                        .eventCategory("web")
                        .eventAction("manifest_get")
                        .eventOutcome("failure")
                        .field("repository.name", this.rname)
                        .field("container.image.name", this.name)
                        .field("container.image.hash.all", ref.digest())
                        .field("url.original", this.upstreamUrl)
                        .error(throwable)
                        .log();
                    result = this.cache.manifests().get(ref);
                }
                return result;
            }
        ).thenCompose(Function.identity());
    }

    @Override
    public CompletableFuture<Tags> tags(Pagination pagination) {
        return new JoinedTagsSource(
            this.name, pagination, this.origin.manifests(), this.cache.manifests()
        ).tags();
    }

    /**
     * Copy manifest by reference from original to cache.
     *
     * @param ref Manifest reference.
     * @param owner Authenticated user login captured from request thread.
     * @return Copy completion.
     */
    private CompletionStage<Void> copy(final ManifestReference ref, final String owner) {
        return this.origin.manifests().get(ref)
            .thenApply(Optional::get)
            .thenCompose(manifest -> this.copySequentially(ref, manifest, owner))
            .handle(
                (ignored, ex) -> {
                    if (ex != null) {
                        EcsLogger.error("com.auto1.pantera.docker")
                            .message("Failed to cache manifest")
                            .eventCategory("web")
                            .eventAction("manifest_cache")
                            .eventOutcome("failure")
                            .field("repository.name", this.rname)
                            .field("container.image.name", this.name)
                            .field("container.image.hash.all", ref.digest())
                            .error(ex)
                            .log();
                    }
                    return null;
                }
            );
    }

    /**
     * Cache manifest JSON and record events. Blobs are now cached via CachingBlob
     * on first access, so no separate blob pre-fetching is needed.
     *
     * @param ref Manifest reference
     * @param manifest The manifest
     * @param owner Authenticated user login captured from request thread.
     * @return Completion when manifest is cached
     */
    private CompletionStage<Void> copySequentially(
        final ManifestReference ref,
        final Manifest manifest,
        final String owner
    ) {
        final boolean needRelease = this.events.isPresent() || this.inspector.isPresent();
        final CompletionStage<Optional<Long>> release = needRelease
            ? this.releaseTimestamp(manifest)
                .exceptionally(ex -> {
                    EcsLogger.warn("com.auto1.pantera.docker")
                        .message("Failed to extract release timestamp")
                        .eventCategory("web")
                        .eventAction("manifest_cache")
                        .eventOutcome("failure")
                        .field("repository.name", this.rname)
                        .field("container.image.name", this.name)
                        .field("container.image.hash.all", ref.digest())
                        .field("error.message", ex.getMessage())
                        .log();
                    return Optional.empty();
                })
            : CompletableFuture.completedFuture(Optional.empty());
        return release.thenCompose(
            rel -> this.finalizeManifestCache(ref, manifest, rel, owner)
        );
    }

    /**
     * Finalize manifest caching: save manifest and record events.
     * This method avoids blocking calls by using async composition.
     *
     * @param ref Manifest reference
     * @param manifest The manifest
     * @param rel Release timestamp from config
     * @param owner Authenticated user login captured from request thread.
     * @return Completion when manifest is saved and events recorded
     */
    private CompletionStage<Void> finalizeManifestCache(
        final ManifestReference ref,
        final Manifest manifest,
        final Optional<Long> rel,
        final String owner
    ) {
        // Get inspector release date asynchronously (FIX: removed blocking .join())
        final CompletionStage<Optional<Long>> inspectorReleaseFuture = this.inspector
            .map(ins -> ins.releaseDate(this.name, ref.digest())
                .thenApply(opt -> opt.map(Instant::toEpochMilli)))
            .orElse(CompletableFuture.completedFuture(Optional.empty()));

        return inspectorReleaseFuture.thenCompose(inspectorRelease -> {
            final Optional<Long> effectiveRelease = rel.isPresent() ? rel : inspectorRelease;
            effectiveRelease.ifPresent(
                millis -> this.inspector.ifPresent(ins -> {
                    final Instant instant = Instant.ofEpochMilli(millis);
                    ins.recordRelease(this.name, ref.digest(), instant);
                    ins.recordRelease(this.name, manifest.digest().string(), instant);
                })
            );
            final CompletionStage<Long> sizeFuture = manifest.isManifestList()
                ? resolveManifestListSize(this.origin, manifest)
                : CompletableFuture.completedFuture(
                    manifest.layers().stream().mapToLong(ManifestLayer::size).sum()
                );
            return sizeFuture.thenCompose(size -> {
                this.events.filter(q -> ImageTag.valid(ref.digest())).ifPresent(queue -> {
                    final long created = System.currentTimeMillis();
                    // Get owner: 1. From inspector cache (skip UNKNOWN), 2. From request thread, 3. Default
                    // Inspector may store UNKNOWN when DockerProxyCooldownSlice resolves the user
                    // from pre-auth headers (Bearer token users have no pantera_login there).
                    // Filter out UNKNOWN so we fall through to requestOwner from MDC.
                    String effectiveOwner = this.inspector
                        .flatMap(inspector -> inspector.ownerFor(this.rname, ref.digest()))
                        .filter(o -> !ArtifactEvent.DEF_OWNER.equals(o))
                        .orElse(null);
                    if (effectiveOwner == null || effectiveOwner.isEmpty()) {
                        if (owner != null && !owner.isEmpty() && !"anonymous".equals(owner)) {
                            effectiveOwner = owner;
                        } else {
                            effectiveOwner = ArtifactEvent.DEF_OWNER;
                        }
                    }
                    queue.add( // ok: unbounded ConcurrentLinkedDeque (ArtifactEvent queue)
                        new ArtifactEvent(
                            CacheManifests.REPO_TYPE,
                            this.rname,
                            effectiveOwner,
                            this.name,
                            ref.digest(),
                            size,
                            created,
                            effectiveRelease.orElse(null)
                        )
                    );
                });
                return this.cache.manifests().putUnchecked(ref, manifest.content())
                    .thenApply(ignored -> null);
            });
        });
    }

    private CompletionStage<Optional<Long>> releaseTimestamp(final Manifest manifest) {
        if (manifest.isManifestList()) {
            // Multi-arch tags (e.g. ubuntu:latest, python:3.12) resolve to
            // an OCI image index / Docker manifest list with no `config`
            // blob. Historically we returned empty here, which caused
            // cooldown to fail-open for every multi-arch image on first
            // pull. Resolve through: fetch the first child (typically
            // linux/amd64) and read its config's `created` timestamp.
            // All children of a single tag share a build run, so any
            // one of them gives a representative release date.
            return this.firstChildReleaseTimestamp(manifest);
        }
        return this.origin.layers().get(manifest.config()).thenCompose(
            blob -> {
                if (blob.isEmpty()) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                return blob.get().content()
                    .thenCompose(Content::asBytesFuture)
                    .thenApply(this::extractCreatedTimestamp);
            }
        );
    }

    /**
     * Resolve a manifest list's release timestamp by fetching the first
     * child manifest and reading its image-config {@code created} field.
     * Returns empty on any upstream failure — never throws — so the
     * caller falls through to the existing "no release date → allow"
     * branch rather than blocking on a transient upstream blip.
     *
     * <p>Cost is bounded: the result is cached in
     * {@link DockerProxyCooldownInspector}'s Caffeine cache
     * ({@code maximumSize=10_000}, {@code expireAfterWrite=24h}), so at
     * most one extra upstream pair per {@code image:tag} per day. The
     * child manifest and its config blob are also warmed in the
     * registry cache for the downstream pull.</p>
     */
    private CompletionStage<Optional<Long>> firstChildReleaseTimestamp(
        final Manifest manifestList
    ) {
        final Collection<Digest> children = manifestList.manifestListChildren();
        if (children.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final Digest firstChild = children.iterator().next();
        return this.origin.manifests()
            .get(ManifestReference.from(firstChild))
            .thenCompose(opt -> {
                // Defensive: nested manifest lists are not defined by
                // OCI but some registries emit them. Bail out rather
                // than recurse.
                if (opt.isEmpty() || opt.get().isManifestList()) {
                    return CompletableFuture.<Optional<Long>>completedFuture(Optional.empty());
                }
                return this.releaseTimestamp(opt.get());
            })
            .exceptionally(ex -> {
                EcsLogger.debug("com.auto1.pantera.docker")
                    .message("Could not resolve child manifest for release date")
                    .eventCategory("web")
                    .eventAction("manifest_cache")
                    .field("repository.name", this.rname)
                    .field("container.image.name", this.name)
                    .field("container.image.hash.all", firstChild.string())
                    .field("error.message", ex.getMessage())
                    .log();
                return Optional.empty();
            });
    }

    private Optional<Long> extractCreatedTimestamp(final byte[] config) {
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(config))) {
            final JsonObject json = reader.readObject();
            final String created = json.getString("created", null);
            if (created != null && !created.isEmpty()) {
                return Optional.of(Instant.parse(created).toEpochMilli());
            }
        } catch (final DateTimeParseException | JsonException ex) {
            EcsLogger.debug("com.auto1.pantera.docker")
                .message("Unable to parse manifest config `created` field")
                .eventCategory("web")
                .eventAction("manifest_cache")
                .field("repository.name", this.rname)
                .field("container.image.name", this.name)
                .field("error.message", ex.getMessage())
                .log();
        }
        return Optional.empty();
    }

    /**
     * Resolve total size of a manifest list by fetching child manifests
     * from the origin repo and summing their layer sizes.
     *
     * @param repo Repository containing the child manifests
     * @param manifestList The manifest list
     * @return Future with total size in bytes
     */
    private static CompletableFuture<Long> resolveManifestListSize(
        final Repo repo, final Manifest manifestList
    ) {
        final Collection<Digest> children = manifestList.manifestListChildren();
        if (children.isEmpty()) {
            return CompletableFuture.completedFuture(0L);
        }
        CompletableFuture<Long> result = CompletableFuture.completedFuture(0L);
        for (final Digest child : children) {
            result = result.thenCompose(
                running -> repo.manifests()
                    .get(ManifestReference.from(child))
                    .thenApply(opt -> {
                        if (opt.isPresent() && !opt.get().isManifestList()) {
                            return running + opt.get().layers().stream()
                                .mapToLong(ManifestLayer::size).sum();
                        }
                        return running;
                    })
                    .exceptionally(ex -> running)
            );
        }
        return result;
    }

    /**
     * Record proxy request metric.
     */
    private void recordProxyMetric(final String result, final long duration) {
        this.recordMetric(() -> {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.rname, this.upstreamUrl, result, duration);
            }
        });
    }

    /**
     * Record upstream error metric.
     */
    private void recordUpstreamErrorMetric(final Throwable error) {
        this.recordMetric(() -> {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                String errorType = "unknown";
                if (error instanceof java.util.concurrent.TimeoutException) {
                    errorType = "timeout";
                } else if (error instanceof java.net.ConnectException) {
                    errorType = "connection";
                }
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordUpstreamError(this.rname, this.upstreamUrl, errorType);
            }
        });
    }

    /**
     * Record metric safely (only if metrics are enabled).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.docker")
                .message("Failed to record metric")
                .error(ex)
                .log();
        }
    }
}
