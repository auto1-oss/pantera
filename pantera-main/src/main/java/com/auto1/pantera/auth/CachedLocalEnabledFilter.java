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

import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cache.GlobalCacheConfig;
import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.log.EcsLogger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Cached decorator for {@link LocalEnabledFilter} — caches the boolean
 * {@code enabled} outcome per username in a Caffeine L1 (and optional
 * Valkey L2) so the JDBC lookup in {@code LocalEnabledFilter} does not
 * fire on every authenticated request (CLI basic auth pulls hit this on
 * EVERY request).
 *
 * <p><b>Only caches the "enabled" dimension</b> — never caches failed
 * authentication from the delegate. Caching auth failures would be
 * DoS-amplifying: a single wrong password could then gate access even
 * if the user fixes it within the TTL, and negative credential caching
 * can hide password rotations.
 *
 * <p>Flow on {@link #user(String, String)}:
 * <ol>
 *   <li>Delegate authentication (password check) runs first. If it
 *       returns empty, we return empty — never touch the cache.</li>
 *   <li>If present, probe L1 by username: {@code Boolean.TRUE} → pass
 *       through; {@code Boolean.FALSE} → return empty. (L1 cache stores
 *       the outcome of the enabled check from a previous call.)</li>
 *   <li>On L1 miss, probe L2 (Valkey) with a bounded timeout. Populate
 *       L1 on hit. Return empty on disabled.</li>
 *   <li>On total miss, since the delegate already authenticated
 *       successfully ({@code LocalEnabledFilter} would have rejected if
 *       the user were disabled), treat the outcome as enabled=true.
 *       Populate L1 and L2.</li>
 * </ol>
 *
 * <p>{@link #invalidate(String)} drops the entry from L1, DELs L2, and
 * broadcasts on pub/sub so peer nodes drop their L1 copies.
 *
 * @since 2.2.0
 */
public final class CachedLocalEnabledFilter implements Authentication {

    /**
     * Pub/sub / L2 namespace used for this cache's keys and invalidation
     * messages.
     */
    public static final String NAMESPACE = "auth:enabled";

    /**
     * Inner authentication (typically {@link LocalEnabledFilter}).
     * Authenticates credentials AND runs the enabled-check JDBC lookup.
     */
    private final Authentication delegate;

    /**
     * L1 in-memory cache: username → enabled flag.
     */
    private final Cache<String, Boolean> l1;

    /**
     * L2 Valkey async commands, or {@code null} when two-tier disabled.
     * {@link ValkeyConnection#async()} returns {@code <String, byte[]>}.
     */
    private final RedisAsyncCommands<String, byte[]> l2;

    /**
     * Whether the L2 (Valkey) tier is enabled.
     */
    private final boolean twoTier;

    /**
     * Bound on synchronous L2 operations (ms). Misses time out to a
     * delegate fallback instead of blocking the auth thread.
     */
    private final long l2TimeoutMs;

    /**
     * L2 TTL applied on writes, in seconds.
     */
    private final long l2TtlSeconds;

    /**
     * Pub/sub for cross-instance invalidation; nullable when disabled.
     */
    private final CacheInvalidationPubSub pubsub;

    /**
     * Ctor.
     * @param delegate Inner authentication (typically {@link LocalEnabledFilter})
     * @param cfg Global cache config
     * @param valkey Optional Valkey connection for L2
     * @param pubsub Optional pub/sub for cross-instance invalidation
     */
    public CachedLocalEnabledFilter(
        final Authentication delegate,
        final GlobalCacheConfig cfg,
        final ValkeyConnection valkey,
        final CacheInvalidationPubSub pubsub
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        final GlobalCacheConfig.AuthEnabledConfig ac =
            Objects.requireNonNull(cfg, "cfg").authEnabled();
        this.twoTier = valkey != null && ac.l2Enabled();
        this.l2 = this.twoTier ? valkey.async() : null;
        this.l2TimeoutMs = ac.l2TimeoutMs();
        this.l2TtlSeconds = ac.l2TtlSeconds();
        this.pubsub = pubsub;
        this.l1 = Caffeine.newBuilder()
            .maximumSize(ac.l1MaxSize())
            .expireAfterWrite(Duration.ofSeconds(ac.l1TtlSeconds()))
            .recordStats()
            .build();
        if (pubsub != null) {
            pubsub.subscribe(NAMESPACE, key -> this.l1.invalidate(key));
        }
        EcsLogger.info("com.auto1.pantera.auth")
            .message("CachedLocalEnabledFilter initialized"
                + " (twoTier=" + this.twoTier
                + ", l1MaxSize=" + ac.l1MaxSize()
                + ", l1TtlSeconds=" + ac.l1TtlSeconds()
                + ", l2TtlSeconds=" + ac.l2TtlSeconds()
                + ", l2TimeoutMs=" + ac.l2TimeoutMs() + ")")
            .eventCategory("authentication")
            .eventAction("auth_cache_init")
            .log();
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        // 1. Authenticate through delegate FIRST. If the credentials are
        //    wrong (or the user is disabled and the delegate rejects
        //    them), return empty immediately — never cache auth failures
        //    (DoS-amplification risk; see class javadoc).
        final Optional<AuthUser> authed = this.delegate.user(username, password);
        if (authed.isEmpty()) {
            return authed;
        }
        if (username == null) {
            return authed;
        }
        final String key = username;
        // 2. Probe L1 for the cached enabled-flag outcome.
        final Boolean l1hit = this.l1.getIfPresent(key);
        if (Boolean.TRUE.equals(l1hit)) {
            return authed;
        }
        if (Boolean.FALSE.equals(l1hit)) {
            // Cached disabled — reject even though delegate authenticated.
            // This guards against a stale enabled-flag decision from a
            // non-LocalEnabledFilter layer in the chain.
            return Optional.empty();
        }
        // 3. L1 miss — probe L2 (bounded).
        if (this.twoTier) {
            try {
                final byte[] bytes = this.l2.get(NAMESPACE + ":" + key)
                    .toCompletableFuture()
                    .get(this.l2TimeoutMs, TimeUnit.MILLISECONDS);
                if (bytes != null && bytes.length >= 1) {
                    final boolean enabled = bytes[0] != 0;
                    this.l1.put(key, enabled);
                    return enabled ? authed : Optional.empty();
                }
            } catch (final Exception ex) {
                // L2 outage — treat as miss, fall through.
                EcsLogger.debug("com.auto1.pantera.auth")
                    .message("L2 probe failed for auth-enabled; falling through")
                    .eventCategory("database")
                    .eventAction("cache_l2_probe")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .error(ex)
                    .log();
            }
        }
        // 4. Total miss: delegate returned present, so enabled=true at
        //    the time of the call. Populate L1 and L2.
        this.l1.put(key, Boolean.TRUE);
        if (this.twoTier) {
            try {
                this.l2.setex(
                    NAMESPACE + ":" + key,
                    this.l2TtlSeconds,
                    new byte[] { (byte) 1 }
                );
            } catch (final Exception ex) {
                EcsLogger.debug("com.auto1.pantera.auth")
                    .message("L2 write failed for auth-enabled; L1 kept")
                    .eventCategory("database")
                    .eventAction("cache_l2_write")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .error(ex)
                    .log();
            }
        }
        return authed;
    }

    @Override
    public boolean canHandle(final String username) {
        return this.delegate.canHandle(username);
    }

    @Override
    public boolean isAuthoritative(final String username) {
        return this.delegate.isAuthoritative(username);
    }

    @Override
    public Collection<String> userDomains() {
        return this.delegate.userDomains();
    }

    /**
     * Drop the cached enabled flag for {@code username} in L1 and L2,
     * and broadcast the invalidation on pub/sub so peer nodes drop
     * their L1 copies. Called by admin flows that mutate the user's
     * enabled state (update, enable, disable, delete).
     *
     * @param username User whose cache entry to drop
     */
    public void invalidate(final String username) {
        if (username == null) {
            return;
        }
        this.l1.invalidate(username);
        if (this.twoTier) {
            try {
                this.l2.del(NAMESPACE + ":" + username);
            } catch (final Exception ex) {
                EcsLogger.debug("com.auto1.pantera.auth")
                    .message("L2 delete failed for auth-enabled")
                    .eventCategory("database")
                    .eventAction("cache_l2_delete")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .error(ex)
                    .log();
            }
        }
        if (this.pubsub != null) {
            this.pubsub.publish(NAMESPACE, username);
        }
    }

    @Override
    public String toString() {
        return String.format(
            "%s(size=%d,twoTier=%s),delegate=%s",
            this.getClass().getSimpleName(),
            this.l1.estimatedSize(), this.twoTier, this.delegate
        );
    }
}
