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
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.composite.MultiReadDocker;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.ImageTag;
import com.auto1.pantera.docker.misc.Pagination;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * A docker-proxy repository as a request reads it: its cache first, then its
 * remotes, where a cached tag older than the tag refresh age is checked
 * against the remotes before it is served.
 *
 * <p>A digest, a tag the cache does not hold and every other read are
 * served as {@link MultiReadDocker} serves them. A cached tag checked less
 * than the age ago ({@link TagChecks}) is served from the cache. An older one
 * is checked against the remotes in their order, each through
 * {@link CacheManifests#refresh}: the first remote that has the tag decides
 * what is served; when none has it or none can be asked, the cached
 * manifest is served.</p>
 *
 * @since 2.2.15
 */
public final class TagRefreshDocker implements Docker {

    /**
     * The repository's cache storage.
     */
    private final Docker local;

    /**
     * The remotes, as the request reads a manifest the cache does not hold.
     */
    private final Docker remotes;

    /**
     * The remotes, as they bring a cached tag up to date.
     */
    private final List<CacheDocker> refreshers;

    /**
     * When the tags were last checked.
     */
    private final TagChecks checks;

    /**
     * Whether the checks of this request count (false for a Troubleshoot
     * replay: it asks, but the next client's request asks again).
     */
    private final boolean counted;

    /**
     * Ctor.
     *
     * @param local The repository's cache storage
     * @param remotes The remotes, as the request reads a manifest the cache
     *  does not hold
     * @param refreshers The remotes, as they bring a cached tag up to date
     * @param checks When the tags were last checked
     * @param counted Whether the checks of this request count
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public TagRefreshDocker(
        final Docker local, final Docker remotes, final List<CacheDocker> refreshers,
        final TagChecks checks, final boolean counted
    ) {
        this.local = local;
        this.remotes = remotes;
        this.refreshers = refreshers;
        this.checks = checks;
        this.counted = counted;
    }

    @Override
    public String registryName() {
        return this.local.registryName();
    }

    @Override
    public Repo repo(final String name) {
        final Repo both = new MultiReadDocker(this.local, this.remotes).repo(name);
        final Manifests manifests = new Refreshing(
            name,
            this.local.repo(name).manifests(),
            this.remotes.repo(name).manifests(),
            both.manifests(),
            this.refreshers.stream().map(cached -> cached.repo(name).manifests()).toList()
        );
        return new Repo() {
            @Override
            public Layers layers() {
                return both.layers();
            }

            @Override
            public Manifests manifests() {
                return manifests;
            }

            @Override
            public Uploads uploads() {
                return both.uploads();
            }
        };
    }

    @Override
    public CompletableFuture<Catalog> catalog(final Pagination pagination) {
        return new MultiReadDocker(this.local, this.remotes).catalog(pagination);
    }

    /**
     * Manifests of one image.
     *
     * @since 2.2.15
     */
    private final class Refreshing implements Manifests {

        /**
         * Image name.
         */
        private final String name;

        /**
         * The cache.
         */
        private final Manifests cache;

        /**
         * The remotes, as the request reads them.
         */
        private final Manifests remote;

        /**
         * The cache, then the remotes.
         */
        private final Manifests both;

        /**
         * The remotes, as they bring a cached tag up to date.
         */
        private final List<CacheManifests> refresh;

        /**
         * Ctor.
         *
         * @param name Image name
         * @param cache The cache
         * @param remote The remotes, as the request reads them
         * @param both The cache, then the remotes
         * @param refresh The remotes, as they bring a cached tag up to date
         * @checkstyle ParameterNumberCheck (5 lines)
         */
        Refreshing(
            final String name, final Manifests cache, final Manifests remote,
            final Manifests both, final List<CacheManifests> refresh
        ) {
            this.name = name;
            this.cache = cache;
            this.remote = remote;
            this.both = both;
            this.refresh = refresh;
        }

        @Override
        public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
            return this.both.put(ref, content);
        }

        @Override
        public CompletableFuture<Collection<String>> delete(final ManifestReference ref) {
            return this.both.delete(ref);
        }

        @Override
        public CompletableFuture<Tags> tags(final Pagination pagination) {
            return this.both.tags(pagination);
        }

        @Override
        public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
            final CompletableFuture<Optional<Manifest>> res;
            if (ImageTag.valid(ref.digest())) {
                // The request's logging context, for the copies a check makes
                // on another thread.
                final Map<String, String> context = MDC.getCopyOfContextMap();
                res = this.cache.get(ref)
                    .handle(
                        (held, err) -> {
                            final CompletionStage<Optional<Manifest>> next;
                            if (err != null) {
                                // A cache that failed may hold the tag: read
                                // as the cache and the remotes are read
                                // without a refresh, so the failure is
                                // the answer when no remote has the tag.
                                next = Refreshing.within(context, () -> this.both.get(ref));
                            } else if (held.isPresent()) {
                                next = this.held(ref, held.get(), context);
                            } else {
                                next = this.missing(ref, context);
                            }
                            return next;
                        }
                    )
                    .thenCompose(Function.identity())
                    .toCompletableFuture();
            } else {
                res = this.both.get(ref);
            }
            return res;
        }

        /**
         * A tag the cache does not hold: read from the remotes, which also
         * counts as a check.
         *
         * @param ref Tag
         * @param context Logging context of the request: the remotes take
         *  the user and trace of their copies from it
         * @return Manifest
         */
        private CompletionStage<Optional<Manifest>> missing(
            final ManifestReference ref, final Map<String, String> context
        ) {
            return Refreshing.within(context, () -> this.remote.get(ref)).thenApply(
                found -> {
                    if (found.isPresent() && TagRefreshDocker.this.counted) {
                        TagRefreshDocker.this.checks.asked(this.name, ref.digest());
                    }
                    return found;
                }
            );
        }

        /**
         * A tag the cache holds: served, checked first when it is old.
         *
         * @param ref Tag
         * @param cached Cached manifest
         * @param context Logging context of the request
         * @return Manifest to serve
         */
        private CompletionStage<Optional<Manifest>> held(
            final ManifestReference ref, final Manifest cached, final Map<String, String> context
        ) {
            final CompletionStage<Optional<Manifest>> res;
            if (TagRefreshDocker.this.checks.young(this.name, ref.digest())) {
                res = CompletableFuture.completedFuture(Optional.of(cached));
            } else if (TagRefreshDocker.this.counted) {
                res = TagRefreshDocker.this.checks.check(
                    this.name, ref.digest(), () -> this.from(0, ref, cached, context)
                );
            } else {
                res = this.from(0, ref, cached, context);
            }
            return res;
        }

        /**
         * Check a cached tag against the remotes from one of them on.
         *
         * @param idx Index of the remote to ask
         * @param ref Tag
         * @param cached Cached manifest
         * @param context Logging context of the request
         * @return Manifest to serve: what the first remote that has the tag
         *  decides, the cached manifest when none has it or none answers
         */
        private CompletionStage<Optional<Manifest>> from(
            final int idx, final ManifestReference ref, final Manifest cached,
            final Map<String, String> context
        ) {
            final CompletionStage<Optional<Manifest>> res;
            if (idx < this.refresh.size()) {
                res = Refreshing.within(context, () -> this.refresh.get(idx).refresh(ref, cached))
                    .handle(
                        (found, err) -> err == null && found.isPresent()
                            ? CompletableFuture.completedFuture(found)
                            : this.from(idx + 1, ref, cached, context)
                    )
                    .thenCompose(Function.identity());
            } else {
                res = CompletableFuture.completedFuture(Optional.of(cached));
            }
            return res;
        }

        /**
         * Start a step under the request's logging context and give the
         * thread its own back.
         *
         * @param context Logging context of the request, nullable
         * @param step Step
         * @return What the step started
         */
        private static CompletionStage<Optional<Manifest>> within(
            final Map<String, String> context,
            final Supplier<? extends CompletionStage<Optional<Manifest>>> step
        ) {
            final Map<String, String> own = MDC.getCopyOfContextMap();
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                return step.get();
            } catch (final RuntimeException ex) {
                return CompletableFuture.failedFuture(ex);
            } finally {
                if (own == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(own);
                }
            }
        }
    }
}
