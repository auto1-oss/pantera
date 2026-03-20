/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cache;

import com.auto1.pantera.asto.misc.Cleanable;

/**
 * Decorator that broadcasts cache invalidation to other Artipie instances
 * via Redis pub/sub, in addition to performing the local invalidation.
 * <p>
 * When {@link #invalidate(String)} or {@link #invalidateAll()} is called,
 * this wrapper:
 * <ol>
 *   <li>Invalidates the local cache (delegates to wrapped instance)</li>
 *   <li>Publishes a message to Redis so other instances invalidate too</li>
 * </ol>
 * <p>
 * The {@link CacheInvalidationPubSub} subscriber registers the <b>inner</b>
 * (unwrapped) cache, so remote messages bypass this decorator and don't
 * re-publish — preventing infinite loops.
 *
 * @since 1.20.13
 */
public final class PublishingCleanable implements Cleanable<String> {

    /**
     * Inner cache to delegate to.
     */
    private final Cleanable<String> inner;

    /**
     * Pub/sub channel to publish invalidation messages.
     */
    private final CacheInvalidationPubSub pubsub;

    /**
     * Cache type name (e.g. "auth", "filters", "policy").
     */
    private final String cacheType;

    /**
     * Ctor.
     * @param inner Local cache to wrap
     * @param pubsub Redis pub/sub channel
     * @param cacheType Cache type identifier
     */
    public PublishingCleanable(final Cleanable<String> inner,
        final CacheInvalidationPubSub pubsub, final String cacheType) {
        this.inner = inner;
        this.pubsub = pubsub;
        this.cacheType = cacheType;
    }

    @Override
    public void invalidate(final String key) {
        this.inner.invalidate(key);
        this.pubsub.publish(this.cacheType, key);
    }

    @Override
    public void invalidateAll() {
        this.inner.invalidateAll();
        this.pubsub.publishAll(this.cacheType);
    }
}
