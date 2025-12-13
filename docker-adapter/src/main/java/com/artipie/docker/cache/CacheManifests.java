/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.cache;

import com.artipie.asto.Content;
import com.artipie.docker.Digest;
import com.artipie.docker.ManifestReference;
import com.artipie.docker.Manifests;
import com.artipie.docker.Repo;
import com.artipie.docker.Tags;
import com.artipie.docker.asto.CheckedBlobSource;
import com.artipie.docker.manifest.Manifest;
import com.artipie.docker.manifest.ManifestLayer;
import com.artipie.docker.misc.JoinedTagsSource;
import com.artipie.docker.misc.Pagination;
import com.artipie.http.log.EcsLogger;
import com.artipie.scheduling.ArtifactEvent;
import org.slf4j.MDC;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
     * Artipie repository name.
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
     * @param registryName Artipie repository name
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
     * @param registryName Artipie repository name
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
        return this.origin.manifests().get(ref).handle(
            (original, throwable) -> {
                final long duration = System.currentTimeMillis() - startTime;
                final CompletionStage<Optional<Manifest>> result;
                if (throwable == null) {
                    if (original.isPresent()) {
                        this.recordProxyMetric("success", duration);
                        Manifest manifest = original.get();
                        if (Manifest.MANIFEST_SCHEMA2.equals(manifest.mediaType()) ||
                            Manifest.MANIFEST_OCI_V1.equals(manifest.mediaType()) ||
                            Manifest.MANIFEST_LIST_SCHEMA2.equals(manifest.mediaType()) ||
                            Manifest.MANIFEST_OCI_INDEX.equals(manifest.mediaType())) {
                            this.copy(ref);
                            result = CompletableFuture.completedFuture(original);
                        } else {
                            EcsLogger.warn("com.artipie.docker")
                                .message("Cannot add manifest to cache")
                                .eventCategory("repository")
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
                        result = this.cache.manifests().get(ref).exceptionally(ignored -> original);
                    }
                } else {
                    this.recordProxyMetric("exception", duration);
                    this.recordUpstreamErrorMetric(throwable);
                    EcsLogger.error("com.artipie.docker")
                        .message("Failed getting manifest")
                        .eventCategory("repository")
                        .eventAction("manifest_get")
                        .eventOutcome("failure")
                        .field("repository.name", this.rname)
                        .field("container.image.name", this.name)
                        .field("container.image.hash.all", ref.digest())
                        .field("url.upstream", this.upstreamUrl)
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
     * @return Copy completion.
     */
    private CompletionStage<Void> copy(final ManifestReference ref) {
        return this.origin.manifests().get(ref)
            .thenApply(Optional::get)
            .thenCompose(manifest -> this.copySequentially(ref, manifest))
            .handle(
                (ignored, ex) -> {
                    if (ex != null) {
                        EcsLogger.error("com.artipie.docker")
                            .message("Failed to cache manifest")
                            .eventCategory("repository")
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

    private CompletionStage<Void> copySequentially(
        final ManifestReference ref,
        final Manifest manifest
    ) {
        CompletionStage<Void> staged = CompletableFuture.completedFuture(null);
        if (!manifest.isManifestList()) {
            staged = staged.thenCompose(nothing -> this.copy(manifest.config()));
            for (final ManifestLayer layer : manifest.layers()) {
                if (layer.urls().isEmpty()) {
                    staged = staged.thenCompose(nothing -> this.copy(layer.digest()));
                }
            }
        }
        final boolean needRelease = this.events.isPresent() || this.inspector.isPresent();
        final CompletionStage<Optional<Long>> release = needRelease
            ? this.releaseTimestamp(manifest)
                .exceptionally(ex -> {
                    EcsLogger.warn("com.artipie.docker")
                        .message("Failed to extract release timestamp")
                        .eventCategory("repository")
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
        return staged.thenCombine(
            release,
            (ignored, rel) -> rel
        ).thenCompose(
            rel -> {
                final CompletionStage<Manifest> res =
                    this.cache.manifests().put(ref, manifest.content());
                final Optional<Long> inspectorRelease = this.inspector
                    .flatMap(ins -> ins.releaseDate(this.name, ref.digest()).join())
                    .map(Instant::toEpochMilli);
                final Optional<Long> effectiveRelease = rel.isPresent() ? rel : inspectorRelease;
                effectiveRelease.ifPresent(
                    millis -> this.inspector.ifPresent(ins -> {
                        final Instant instant = Instant.ofEpochMilli(millis);
                        ins.recordRelease(this.name, ref.digest(), instant);
                        ins.recordRelease(this.name, manifest.digest().string(), instant);
                    })
                );
                this.events.ifPresent(queue -> {
                    final long created = System.currentTimeMillis();
                    // Get owner: 1. From inspector cache, 2. From MDC (set by auth), 3. Default
                    String owner = this.inspector
                        .flatMap(inspector -> inspector.ownerFor(this.rname, ref.digest()))
                        .orElse(null);
                    if (owner == null || owner.isEmpty()) {
                        final String mdcUser = MDC.get("user.name");
                        if (mdcUser != null && !mdcUser.isEmpty() && !"anonymous".equals(mdcUser)) {
                            owner = mdcUser;
                        } else {
                            owner = ArtifactEvent.DEF_OWNER;
                        }
                    }
                    queue.add(
                        new ArtifactEvent(
                            CacheManifests.REPO_TYPE,
                            this.rname,
                            owner,
                            this.name,
                            ref.digest(),
                            manifest.isManifestList()
                                ? 0L
                                : manifest.layers().stream().mapToLong(ManifestLayer::size).sum(),
                            created,
                            effectiveRelease.orElse(null)
                        )
                    );
                });
                return res.thenApply(ignored -> null);
            }
        );
    }

    /**
     * Copy blob by digest from original to cache.
     *
     * @param digest Blob digest.
     * @return Copy completion.
     */
    private CompletionStage<Void> copy(final Digest digest) {
        return this.origin.layers().get(digest).thenCompose(
            blob -> {
                if (blob.isEmpty()) {
                    throw new IllegalArgumentException(
                        String.format("Failed loading blob %s", digest)
                    );
                }
                return blob.get().content();
            }
        ).thenCompose(
            content -> this.cache.layers().put(new CheckedBlobSource(content, digest))
        ).thenCompose(
            blob -> CompletableFuture.allOf()
        );
    }

    private CompletionStage<Optional<Long>> releaseTimestamp(final Manifest manifest) {
        if (manifest.isManifestList()) {
            return CompletableFuture.completedFuture(Optional.empty());
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

    private Optional<Long> extractCreatedTimestamp(final byte[] config) {
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(config))) {
            final JsonObject json = reader.readObject();
            final String created = json.getString("created", null);
            if (created != null && !created.isEmpty()) {
                return Optional.of(Instant.parse(created).toEpochMilli());
            }
        } catch (final DateTimeParseException | JsonException ex) {
            EcsLogger.debug("com.artipie.docker")
                .message("Unable to parse manifest config `created` field")
                .eventCategory("repository")
                .eventAction("manifest_cache")
                .field("repository.name", this.rname)
                .field("container.image.name", this.name)
                .field("error.message", ex.getMessage())
                .log();
        }
        return Optional.empty();
    }

    /**
     * Record proxy request metric.
     */
    private void recordProxyMetric(final String result, final long duration) {
        this.recordMetric(() -> {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.rname, this.upstreamUrl, result, duration);
            }
        });
    }

    /**
     * Record upstream error metric.
     */
    private void recordUpstreamErrorMetric(final Throwable error) {
        this.recordMetric(() -> {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                String errorType = "unknown";
                if (error instanceof java.util.concurrent.TimeoutException) {
                    errorType = "timeout";
                } else if (error instanceof java.net.ConnectException) {
                    errorType = "connection";
                }
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordUpstreamError(this.rname, this.upstreamUrl, errorType);
            }
        });
    }

    /**
     * Record metric safely (only if metrics are enabled).
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private void recordMetric(final Runnable metric) {
        try {
            if (com.artipie.metrics.ArtipieMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            // Ignore metric errors - don't fail requests
        }
    }
}
