/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.composite;

import com.artipie.asto.Content;
import com.artipie.docker.ManifestReference;
import com.artipie.docker.Manifests;
import com.artipie.docker.Tags;
import com.artipie.docker.manifest.Manifest;
import com.artipie.docker.misc.JoinedTagsSource;
import com.artipie.docker.misc.Pagination;
import com.artipie.http.log.EcsLogger;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Multi-read {@link Manifests} implementation.
 */
public final class MultiReadManifests implements Manifests {

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Manifests for reading.
     */
    private final List<Manifests> manifests;

    /**
     * Ctor.
     *
     * @param name Repository name.
     * @param manifests Manifests for reading.
     */
    public MultiReadManifests(String name, List<Manifests> manifests) {
        this.name = name;
        this.manifests = manifests;
    }

    @Override
    public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
        // Capture MDC context before crossing the async boundary.
        // supplyAsync() runs on ForkJoinPool which does not inherit thread-local MDC.
        // Without this, CacheManifests.get() captures requestOwner = null → UNKNOWN.
        final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                for (Manifests m : manifests) {
                    Optional<Manifest> res = m.get(ref).handle(
                        (manifest, throwable) -> {
                            final Optional<Manifest> result;
                            if (throwable == null) {
                                result = manifest;
                            } else {
                                EcsLogger.error("com.artipie.docker")
                                    .message("Failed to read manifest")
                                    .eventCategory("repository")
                                    .eventAction("manifest_get")
                                    .eventOutcome("failure")
                                    .field("container.image.hash.all", ref.digest())
                                    .error(throwable)
                                    .log();
                                result = Optional.empty();
                            }
                            return result;
                        }
                    ).toCompletableFuture().join();
                    if (res.isPresent()) {
                        return res;
                    }
                }
                return Optional.empty();
            } finally {
                MDC.clear();
            }
        });
    }

    @Override
    public CompletableFuture<Tags> tags(Pagination pagination) {
        return new JoinedTagsSource(this.name, this.manifests, pagination).tags();
    }
}
