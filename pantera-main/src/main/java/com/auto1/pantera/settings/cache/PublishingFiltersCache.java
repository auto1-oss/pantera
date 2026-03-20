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
package com.auto1.pantera.settings.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.http.filter.Filters;
import java.util.Optional;

/**
 * {@link FiltersCache} decorator that publishes invalidation events
 * to Redis pub/sub for cross-instance cache invalidation.
 *
 * @since 1.20.13
 */
public final class PublishingFiltersCache implements FiltersCache {

    /**
     * Inner cache to delegate to.
     */
    private final FiltersCache inner;

    /**
     * Pub/sub channel.
     */
    private final CacheInvalidationPubSub pubsub;

    /**
     * Ctor.
     * @param inner Local filters cache
     * @param pubsub Redis pub/sub channel
     */
    public PublishingFiltersCache(final FiltersCache inner,
        final CacheInvalidationPubSub pubsub) {
        this.inner = inner;
        this.pubsub = pubsub;
    }

    @Override
    public Optional<Filters> filters(final String reponame, final YamlMapping repoyaml) {
        return this.inner.filters(reponame, repoyaml);
    }

    @Override
    public long size() {
        return this.inner.size();
    }

    @Override
    public void invalidate(final String reponame) {
        this.inner.invalidate(reponame);
        this.pubsub.publish("filters", reponame);
    }

    @Override
    public void invalidateAll() {
        this.inner.invalidateAll();
        this.pubsub.publishAll("filters");
    }
}
