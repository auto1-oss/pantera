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
import com.auto1.pantera.cache.CacheBroadcast;
import com.auto1.pantera.db.dao.RevocationDao;
import com.auto1.pantera.db.dao.RevocationStore;
import com.auto1.pantera.http.log.EcsLogger;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB-durable, Valkey-accelerated revocation blocklist (WS2.1, 2.3.0).
 * <p>
 * Prior to 2.3.0 this class wrote revocations to Valkey only and never read
 * anything back: {@code isRevoked*} checked the local map alone, and a
 * restarted node started with an empty map — re-honoring already-revoked,
 * unexpired tokens for their full TTL. The DB (via {@link RevocationStore},
 * the same table {@link DbRevocationBlocklist} uses) is now the source of
 * truth on every path:
 * <ul>
 *   <li><b>Write</b> — {@link #revokeJti}/{@link #revokeUser} insert the DB
 *       row first, then publish over {@link CacheBroadcast} carrying the
 *       token's real remaining TTL (fixes the pre-2.3.0 fixed-2h peer TTL
 *       bug — a peer used to apply a flat default regardless of how long the
 *       revocation should actually last).</li>
 *   <li><b>Boot hydration</b> — the constructor performs an immediate,
 *       synchronous {@code pollSince(EPOCH)} read (boot thread only, mirrors
 *       {@code CacheInvalidationPubSub}'s own boot-blocking SUBSCRIBE), so a
 *       freshly-started node's local cache starts populated with every
 *       currently-active revocation instead of empty.</li>
 *   <li><b>Reconciliation</b> — every {@code isRevoked*} call triggers a
 *       throttled (5s) incremental re-poll of the DB, exactly mirroring
 *       {@link DbRevocationBlocklist#isRevokedJti}'s own poll-if-stale
 *       pattern. This is the backstop for a missed pub/sub message: within
 *       one poll interval, any revocation this node's pub/sub subscription
 *       dropped is picked up from the DB regardless.</li>
 * </ul>
 * Net effect: a Valkey outage degrades this class to "DB-poll speed"
 * (correct, bounded by the poll interval) — never to fail-open. Cross-node
 * fan-out for the common case is still near-instant, over the existing
 * {@link CacheBroadcast} channel (same channel {@code auth}/{@code filters}/
 * {@code policy} invalidation already uses, cache type {@code "revocation"}).
 *
 * <p>Pub/sub message format (value published on the {@code "revocation"}
 * cache type): {@code jti:{jti}:{ttlSeconds}} / {@code user:{username}:
 * {ttlSeconds}}. A message without the trailing {@code :ttlSeconds} (a v1
 * payload from a pre-2.3.0 peer mid-rolling-upgrade, or a value that
 * legitimately contains no colon-separated suffix) falls back to
 * {@code defaultTtlSeconds} — rolling-upgrade compatible.
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
     * DB entry-type constant for JTI-based revocations, matching
     * {@link DbRevocationBlocklist}.
     */
    private static final String TYPE_JTI = "jti";

    /**
     * DB entry-type constant for user-based revocations, matching
     * {@link DbRevocationBlocklist}.
     */
    private static final String TYPE_USER = "username";

    /**
     * Reconciliation poll throttle, matching
     * {@link DbRevocationBlocklist}'s own interval.
     */
    private static final long DEFAULT_POLL_INTERVAL_MS = 5_000L;

    /**
     * Pub/sub for cross-node revocation propagation.
     */
    private final CacheBroadcast pubSub;

    /**
     * DB-durable store — source of truth.
     */
    private final RevocationStore dao;

    /**
     * Default TTL in seconds used when a remote invalidation arrives without
     * a parseable embedded TTL (legacy v1 payload / malformed message).
     */
    private final int defaultTtlSeconds;

    /**
     * Reconciliation poll throttle in milliseconds. Package-private test
     * seam so propagation tests don't need to wait out the production
     * 5-second interval; production always uses
     * {@link #DEFAULT_POLL_INTERVAL_MS}.
     */
    private final long pollIntervalMs;

    /**
     * Local cache: JTI → expiry instant.
     */
    private final ConcurrentHashMap<String, Instant> jtiCache;

    /**
     * Local cache: username → expiry instant.
     */
    private final ConcurrentHashMap<String, Instant> userCache;

    /**
     * Timestamp of the last successful DB poll.
     */
    private volatile Instant lastPoll;

    /**
     * Ctor. Hydrates the local caches from the DB before returning.
     * @param pubSub Pub/sub channel for cross-node propagation
     * @param dao DB-durable revocation store (source of truth)
     * @param defaultTtlSeconds Fallback TTL for legacy/malformed pub/sub payloads
     */
    public ValkeyRevocationBlocklist(
        final CacheBroadcast pubSub,
        final RevocationStore dao,
        final int defaultTtlSeconds
    ) {
        this(pubSub, dao, defaultTtlSeconds, ValkeyRevocationBlocklist.DEFAULT_POLL_INTERVAL_MS);
    }

    /**
     * Full ctor with an overridable poll interval (test seam).
     * @param pubSub Pub/sub channel for cross-node propagation
     * @param dao DB-durable revocation store (source of truth)
     * @param defaultTtlSeconds Fallback TTL for legacy/malformed pub/sub payloads
     * @param pollIntervalMs Reconciliation poll throttle in milliseconds
     */
    ValkeyRevocationBlocklist(
        final CacheBroadcast pubSub,
        final RevocationStore dao,
        final int defaultTtlSeconds,
        final long pollIntervalMs
    ) {
        this.pubSub = pubSub;
        this.dao = dao;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.pollIntervalMs = pollIntervalMs;
        this.jtiCache = new ConcurrentHashMap<>();
        this.userCache = new ConcurrentHashMap<>();
        this.lastPoll = Instant.EPOCH;
        pubSub.register(CACHE_TYPE, new RevocationCacheHandler());
        // Boot hydration: forces an immediate pollSince(EPOCH) — the full
        // active-revocation set — before this instance answers any check.
        this.pollIfStale();
    }

    @Override
    public boolean isRevokedJti(final String jti) {
        this.pollIfStale();
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
        this.pollIfStale();
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
        this.dao.insert(TYPE_JTI, jti, ttlSeconds);
        this.jtiCache.put(jti, Instant.now().plusSeconds(ttlSeconds));
        this.pubSub.publish(CACHE_TYPE, JTI_PREFIX + jti + ':' + ttlSeconds);
    }

    @Override
    public void revokeUser(final String username, final int ttlSeconds) {
        this.dao.insert(TYPE_USER, username, ttlSeconds);
        this.userCache.put(username, Instant.now().plusSeconds(ttlSeconds));
        this.pubSub.publish(CACHE_TYPE, USER_PREFIX + username + ':' + ttlSeconds);
    }

    /**
     * Poll the DB if more than {@link #pollIntervalMs} ms have elapsed since
     * the last poll. Fetches only entries created after the last poll so the
     * query stays lightweight. Called from every {@code isRevoked*} check
     * (throttled) and once, forced, from the constructor (boot hydration,
     * {@code lastPoll} starts at {@link Instant#EPOCH}).
     */
    private void pollIfStale() {
        final Instant now = Instant.now();
        if (now.toEpochMilli() - this.lastPoll.toEpochMilli() < this.pollIntervalMs) {
            return;
        }
        final Instant pollFrom = this.lastPoll;
        final boolean bootHydration = Instant.EPOCH.equals(pollFrom);
        this.lastPoll = now;
        try {
            final List<RevocationDao.RevocationEntry> entries = this.dao.pollSince(pollFrom);
            for (final RevocationDao.RevocationEntry entry : entries) {
                if (TYPE_JTI.equals(entry.entryType())) {
                    this.jtiCache.put(entry.entryValue(), entry.expiresAt());
                } else if (TYPE_USER.equals(entry.entryType())) {
                    this.userCache.put(entry.entryValue(), entry.expiresAt());
                }
            }
            if (bootHydration) {
                EcsLogger.info("com.auto1.pantera.auth.ValkeyRevocationBlocklist")
                    .message("Revocation blocklist hydrated from DB on boot (entries="
                        + entries.size() + ")")
                    .eventCategory("authentication")
                    .eventAction("revocation_hydrate")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            } else if (!entries.isEmpty()) {
                EcsLogger.warn("com.auto1.pantera.auth.ValkeyRevocationBlocklist")
                    .message("Revocation reconciliation poll applied " + entries.size()
                        + " entr(y/ies) not yet present locally (missed pub/sub message?)")
                    .eventCategory("authentication")
                    .eventAction("revocation_poll_reconcile")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            }
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.auth.ValkeyRevocationBlocklist")
                .message(
                    bootHydration
                        ? "Failed to hydrate revocation blocklist from DB on boot; "
                            + "starting with an empty cache (self-heals on next poll)"
                        : "Failed to poll revocation blocklist from DB"
                )
                .eventCategory("authentication")
                .eventAction(bootHydration ? "revocation_hydrate" : "revocation_poll_reconcile")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Parse a {@code value[:ttlSeconds]} suffix (the part of the wire
     * message after the {@code "jti:"}/{@code "user:"} prefix). Falls back to
     * the whole string with {@link #defaultTtlSeconds} when there is no
     * parseable trailing TTL — a v1 (pre-2.3.0) peer payload during a
     * rolling upgrade, or a value that legitimately contains no
     * colon-separated numeric suffix.
     * @param encoded The prefix-stripped wire value
     * @return Parsed value and TTL
     */
    private ParsedRevocation parseValueAndTtl(final String encoded) {
        final int sep = encoded.lastIndexOf(':');
        if (sep > 0) {
            try {
                return new ParsedRevocation(
                    encoded.substring(0, sep),
                    Integer.parseInt(encoded.substring(sep + 1))
                );
            } catch (final NumberFormatException ex) { // NOPMD EmptyCatchBlock - expected: no parseable trailing TTL, fall through to the whole-string + default-TTL case below
                // Intentionally empty.
            }
        }
        return new ParsedRevocation(encoded, this.defaultTtlSeconds);
    }

    /**
     * Parsed {@code value}/{@code ttlSeconds} pair from a wire message.
     * @param value The JTI or username
     * @param ttlSeconds Remaining TTL in seconds
     */
    private record ParsedRevocation(String value, int ttlSeconds) { }

    /**
     * Handles remote cache invalidation messages for revocations.
     * When another Pantera node calls revokeJti/revokeUser, this handler
     * receives the pub/sub message and updates the local caches on this node.
     */
    private final class RevocationCacheHandler implements Cleanable<String> {

        @Override
        public void invalidate(final String key) {
            if (key.startsWith(JTI_PREFIX)) {
                final ParsedRevocation parsed = ValkeyRevocationBlocklist.this.parseValueAndTtl(
                    key.substring(JTI_PREFIX.length())
                );
                ValkeyRevocationBlocklist.this.jtiCache.put(
                    parsed.value(), Instant.now().plusSeconds(parsed.ttlSeconds())
                );
            } else if (key.startsWith(USER_PREFIX)) {
                final ParsedRevocation parsed = ValkeyRevocationBlocklist.this.parseValueAndTtl(
                    key.substring(USER_PREFIX.length())
                );
                ValkeyRevocationBlocklist.this.userCache.put(
                    parsed.value(), Instant.now().plusSeconds(parsed.ttlSeconds())
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
