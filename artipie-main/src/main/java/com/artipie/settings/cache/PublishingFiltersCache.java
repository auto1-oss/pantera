/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import com.artipie.cache.CacheInvalidationPubSub;
import com.artipie.http.filter.Filters;
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
