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
package com.auto1.pantera.auth;

import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cache.ValkeyConnection;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Valkey pub/sub backed revocation blocklist.
 *
 * <p>Stores revocation entries in Valkey with a TTL so they expire automatically.
 * Uses the existing {@link CacheInvalidationPubSub} channel to propagate revocations
 * to peer Pantera nodes in real time, so every node's local cache is updated within
 * milliseconds of a revocation being issued on any node.
 *
 * <p>Local cache design:
 * <ul>
 *   <li>jtiCache: JTI → expiry {@link Instant}</li>
 *   <li>userCache: username → expiry {@link Instant}</li>
 * </ul>
 *
 * <p>Valkey key format:
 * <ul>
 *   <li>{@code pantera:revoked:jti:{jti}}</li>
 *   <li>{@code pantera:revoked:user:{username}}</li>
 * </ul>
 *
 * <p>Pub/sub message format (value published on the {@code "revocation"} channel):
 * <ul>
 *   <li>{@code jti:{jti}} — to propagate a JTI revocation</li>
 *   <li>{@code user:{username}} — to propagate a user revocation</li>
 * </ul>
 *
 * @since 2.1.0
 */
public final class ValkeyRevocationBlocklist implements RevocationBlocklist {

    /**
     * Pub/sub cache type name used for revocation messages.
     */
    private static final String CACHE_TYPE = "revocation";

    /**
     * Prefix for JTI revocation pub/sub messages and cache keys.
     */
    private static final String JTI_PREFIX = "jti:";

    /**
     * Prefix for user revocation pub/sub messages and cache keys.
     */
    private static final String USER_PREFIX = "user:";

    /**
     * Valkey key prefix for JTI revocation entries.
     */
    private static final String VALKEY_JTI_KEY = "pantera:revoked:jti:";

    /**
     * Valkey key prefix for user revocation entries.
     */
    private static final String VALKEY_USER_KEY = "pantera:revoked:user:";

    /**
     * Valkey connection for async commands.
     */
    private final ValkeyConnection valkey;

    /**
     * Pub/sub for cross-node revocation propagation.
     */
    private final CacheInvalidationPubSub pubSub;

    /**
     * Default TTL in seconds used when a remote invalidation arrives (no TTL in message).
     */
    private final int defaultTtlSeconds;

    /**
     * Local cache: JTI → expiry instant.
     */
    private final ConcurrentHashMap<String, Instant> jtiCache;

    /**
     * Local cache: username → expiry instant.
     */
    private final ConcurrentHashMap<String, Instant> userCache;

    /**
     * Ctor.
     *
     * @param valkey Valkey connection for storing revocation entries
     * @param pubSub Pub/sub channel for cross-node propagation
     * @param defaultTtlSeconds Default TTL applied when a remote invalidation message arrives
     */
    public ValkeyRevocationBlocklist(
        final ValkeyConnection valkey,
        final CacheInvalidationPubSub pubSub,
        final int defaultTtlSeconds
    ) {
        this.valkey = valkey;
        this.pubSub = pubSub;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.jtiCache = new ConcurrentHashMap<>();
        this.userCache = new ConcurrentHashMap<>();
        pubSub.register(CACHE_TYPE, new RevocationCacheHandler());
    }

    @Override
    public boolean isRevokedJti(final String jti) {
        final Instant exp = this.jtiCache.get(jti);
        if (exp == null) {
            return false;
        }
        if (Instant.now().isAfter(exp)) {
            this.jtiCache.remove(jti);
            return false;
        }
        return true;
    }

    @Override
    public boolean isRevokedUser(final String username) {
        final Instant exp = this.userCache.get(username);
        if (exp == null) {
            return false;
        }
        if (Instant.now().isAfter(exp)) {
            this.userCache.remove(username);
            return false;
        }
        return true;
    }

    @Override
    public void revokeJti(final String jti, final int ttlSeconds) {
        this.jtiCache.put(jti, Instant.now().plusSeconds(ttlSeconds));
        this.pubSub.publish(CACHE_TYPE, JTI_PREFIX + jti);
        this.valkey.async().setex(
            VALKEY_JTI_KEY + jti,
            ttlSeconds,
            "1".getBytes()
        );
    }

    @Override
    public void revokeUser(final String username, final int ttlSeconds) {
        this.userCache.put(username, Instant.now().plusSeconds(ttlSeconds));
        this.pubSub.publish(CACHE_TYPE, USER_PREFIX + username);
        this.valkey.async().setex(
            VALKEY_USER_KEY + username,
            ttlSeconds,
            "1".getBytes()
        );
    }

    /**
     * Handles remote cache invalidation messages for revocations.
     * When another Pantera node calls revokeJti/revokeUser, this handler
     * receives the pub/sub message and updates the local caches on this node.
     */
    private final class RevocationCacheHandler implements Cleanable<String> {

        @Override
        public void invalidate(final String key) {
            if (key.startsWith(JTI_PREFIX)) {
                final String jti = key.substring(JTI_PREFIX.length());
                ValkeyRevocationBlocklist.this.jtiCache.put(
                    jti,
                    Instant.now().plusSeconds(
                        ValkeyRevocationBlocklist.this.defaultTtlSeconds
                    )
                );
            } else if (key.startsWith(USER_PREFIX)) {
                final String username = key.substring(USER_PREFIX.length());
                ValkeyRevocationBlocklist.this.userCache.put(
                    username,
                    Instant.now().plusSeconds(
                        ValkeyRevocationBlocklist.this.defaultTtlSeconds
                    )
                );
            }
        }

        @Override
        public void invalidateAll() {
            ValkeyRevocationBlocklist.this.jtiCache.clear();
            ValkeyRevocationBlocklist.this.userCache.clear();
        }
    }
}
