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
import com.auto1.pantera.db.dao.RevocationDao;
import com.auto1.pantera.http.log.EcsLogger;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Valkey pub/sub backed revocation blocklist.
 *
 * <p>Stores revocation entries in Valkey with a TTL so they expire automatically.
 * Uses the existing {@link CacheInvalidationPubSub} channel to propagate revocations
 * to peer Pantera nodes in real time, so every node's local cache is updated within
 * milliseconds of a revocation being issued on any node.
 *
 * <p>Durability (2.2.9, B47): the local cache used to be the only thing ever
 * read — the Valkey entries were written but never loaded, so a restarted
 * node forgot every revocation, and a peer applied a fixed 2-hour TTL and
 * its own receipt time because the message carried neither. Now:
 * <ul>
 *   <li>{@link #restore()} (boot thread) loads every live entry from Valkey
 *       and, when a database is wired, from the {@code revocation_blocklist}
 *       table;</li>
 *   <li>the pub/sub message carries the sender's revocation instant and
 *       expiry ({@link RevocationMessage});</li>
 *   <li>revocations are also written to the database (when wired), so they
 *       survive a Valkey flush or eviction.</li>
 * </ul>
 *
 * <p>Valkey key format:
 * <ul>
 *   <li>{@code pantera:revoked:jti:{jti}} — value {@code 1}</li>
 *   <li>{@code pantera:revoked:user:{username}} — value: revocation instant in
 *       epoch milliseconds (epoch seconds before 2.2.9)</li>
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
     * Valkey key prefix for JTI revocation entries.
     */
    private static final String VALKEY_JTI_KEY = "pantera:revoked:jti:";

    /**
     * Valkey key prefix for user revocation entries.
     */
    private static final String VALKEY_USER_KEY = "pantera:revoked:user:";

    /**
     * DB entry type for JTI revocations (shared with {@link DbRevocationBlocklist}).
     */
    private static final String TYPE_JTI = "jti";

    /**
     * DB entry type for user revocations (shared with {@link DbRevocationBlocklist}).
     */
    private static final String TYPE_USER = "username";

    /**
     * Stored values below this are epoch seconds (pre-2.2.9 format).
     */
    private static final long MILLIS_THRESHOLD = 100_000_000_000L;

    /**
     * Per-command timeout for the boot-time restore.
     */
    private static final long RESTORE_TIMEOUT_SECONDS = 10L;

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.auth";

    /**
     * Valkey connection for async commands.
     */
    private final ValkeyConnection valkey;

    /**
     * Pub/sub for cross-node revocation propagation.
     */
    private final CacheInvalidationPubSub pubSub;

    /**
     * TTL in seconds applied to a pre-2.2.9 remote message (no expiry in it).
     */
    private final int defaultTtlSeconds;

    /**
     * Durable fallback store; {@code null} when no database is wired.
     */
    private final RevocationDao dao;

    /**
     * Local cache: JTI → expiry instant.
     */
    private final ConcurrentHashMap<String, Instant> jtiCache;

    /**
     * Local cache: username → revocation (issued-at cutoff + expiry).
     */
    private final ConcurrentHashMap<String, UserRevocation> userCache;

    /**
     * Ctor without a database.
     *
     * @param valkey Valkey connection for storing revocation entries
     * @param pubSub Pub/sub channel for cross-node propagation
     * @param defaultTtlSeconds TTL applied to a pre-2.2.9 remote message
     */
    public ValkeyRevocationBlocklist(
        final ValkeyConnection valkey,
        final CacheInvalidationPubSub pubSub,
        final int defaultTtlSeconds
    ) {
        this(valkey, pubSub, defaultTtlSeconds, null);
    }

    /**
     * Ctor.
     *
     * @param valkey Valkey connection for storing revocation entries
     * @param pubSub Pub/sub channel for cross-node propagation
     * @param defaultTtlSeconds TTL applied to a pre-2.2.9 remote message
     * @param dao Durable fallback store, or {@code null}
     */
    public ValkeyRevocationBlocklist(
        final ValkeyConnection valkey,
        final CacheInvalidationPubSub pubSub,
        final int defaultTtlSeconds,
        final RevocationDao dao
    ) {
        this.valkey = valkey;
        this.pubSub = pubSub;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.dao = dao;
        this.jtiCache = new ConcurrentHashMap<>();
        this.userCache = new ConcurrentHashMap<>();
        pubSub.register(CACHE_TYPE, new RevocationCacheHandler());
    }

    /**
     * Load every live revocation from Valkey and the database into the local
     * cache. Blocking — call on the boot thread, never the event loop.
     *
     * @return Number of entries loaded
     */
    public int restore() {
        int loaded = 0;
        try {
            loaded += this.restoreFromValkey();
        } catch (final Exception ex) {
            EcsLogger.warn(LOGGER)
                .message("Could not restore token revocations from Valkey")
                .eventCategory("authentication")
                .eventAction("revocation_blocklist_restore")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
        if (this.dao != null) {
            try {
                loaded += this.restoreFromDb();
            } catch (final Exception ex) {
                EcsLogger.warn(LOGGER)
                    .message("Could not restore token revocations from the database")
                    .eventCategory("authentication")
                    .eventAction("revocation_blocklist_restore")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("log.source", "application")
                    .log();
            }
        }
        EcsLogger.info(LOGGER)
            .message("Restored " + loaded + " live token revocation entries")
            .eventCategory("authentication")
            .eventAction("revocation_blocklist_restore")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
        return loaded;
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
    public boolean isRevokedUser(final String username, final Instant issuedAt) {
        final UserRevocation rev = this.userCache.get(username);
        if (rev == null) {
            return false;
        }
        final Instant now = Instant.now();
        if (rev.expired(now)) {
            this.userCache.remove(username, rev);
            return false;
        }
        return rev.revokes(issuedAt, now);
    }

    @Override
    public void revokeJti(final String jti, final int ttlSeconds) {
        final Instant expires = Instant.now().plusSeconds(ttlSeconds);
        this.jtiCache.merge(jti, expires, ValkeyRevocationBlocklist::later);
        this.pubSub.publish(
            CACHE_TYPE, new RevocationMessage(false, jti, expires, expires).encode()
        );
        this.valkey.async().setex(
            VALKEY_JTI_KEY + jti,
            ttlSeconds,
            "1".getBytes(StandardCharsets.UTF_8)
        );
        this.persist(TYPE_JTI, jti, ttlSeconds);
    }

    @Override
    public void revokeUser(final String username, final int ttlSeconds) {
        final Instant now = Instant.now();
        final UserRevocation rev = new UserRevocation(now, now.plusSeconds(ttlSeconds));
        this.userCache.merge(username, rev, UserRevocation::merge);
        this.pubSub.publish(
            CACHE_TYPE,
            new RevocationMessage(true, username, rev.revokedAt(), rev.expiresAt()).encode()
        );
        this.valkey.async().set(
            VALKEY_USER_KEY + username,
            Long.toString(now.toEpochMilli()).getBytes(StandardCharsets.UTF_8),
            SetArgs.Builder.ex(ttlSeconds)
        );
        this.persist(TYPE_USER, username, ttlSeconds);
    }

    /**
     * Write-through to the durable fallback store. A failure is logged, not
     * thrown: the revocation is already live locally, in Valkey and on peers.
     *
     * @param type Entry type
     * @param value JTI or username
     * @param ttlSeconds Lifetime
     */
    private void persist(final String type, final String value, final int ttlSeconds) {
        if (this.dao == null) {
            return;
        }
        try {
            this.dao.insert(type, value, ttlSeconds);
        } catch (final Exception ex) {
            EcsLogger.warn(LOGGER)
                .message("Could not persist token revocation to the database;"
                    + " it is held in Valkey only")
                .eventCategory("authentication")
                .eventAction("token_revoke")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * SCAN both key families and load each live entry with its remaining TTL.
     *
     * @return Entries loaded
     * @throws Exception On a Valkey failure or timeout
     */
    private int restoreFromValkey() throws Exception {
        int loaded = 0;
        for (final String key : this.scan(VALKEY_USER_KEY + "*")) {
            final Long pttl = this.valkey.async().pttl(key)
                .get(RESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            final byte[] raw = this.valkey.async().get(key)
                .get(RESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (pttl == null || pttl <= 0 || raw == null) {
                continue;
            }
            final Instant now = Instant.now();
            final long stored = Long.parseLong(new String(raw, StandardCharsets.UTF_8).trim());
            final Instant revoked = stored < MILLIS_THRESHOLD
                ? Instant.ofEpochSecond(stored) : Instant.ofEpochMilli(stored);
            this.userCache.merge(
                key.substring(VALKEY_USER_KEY.length()),
                new UserRevocation(revoked, now.plusMillis(pttl)),
                UserRevocation::merge
            );
            loaded += 1;
        }
        for (final String key : this.scan(VALKEY_JTI_KEY + "*")) {
            final Long pttl = this.valkey.async().pttl(key)
                .get(RESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (pttl == null || pttl <= 0) {
                continue;
            }
            this.jtiCache.merge(
                key.substring(VALKEY_JTI_KEY.length()),
                Instant.now().plusMillis(pttl),
                ValkeyRevocationBlocklist::later
            );
            loaded += 1;
        }
        return loaded;
    }

    /**
     * Load every live DB entry.
     *
     * @return Entries loaded
     */
    private int restoreFromDb() {
        int loaded = 0;
        for (final RevocationDao.RevocationEntry entry : this.dao.pollSince(Instant.EPOCH)) {
            if (TYPE_JTI.equals(entry.entryType())) {
                this.jtiCache.merge(
                    entry.entryValue(), entry.expiresAt(), ValkeyRevocationBlocklist::later
                );
                loaded += 1;
            } else if (TYPE_USER.equals(entry.entryType())) {
                this.userCache.merge(
                    entry.entryValue(),
                    new UserRevocation(entry.createdAt(), entry.expiresAt()),
                    UserRevocation::merge
                );
                loaded += 1;
            }
        }
        return loaded;
    }

    /**
     * All keys matching a pattern (cursor SCAN, never KEYS).
     *
     * @param pattern Glob pattern
     * @return Matching keys
     * @throws Exception On a Valkey failure or timeout
     */
    private java.util.List<String> scan(final String pattern) throws Exception {
        final java.util.List<String> keys = new java.util.ArrayList<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        do {
            final KeyScanCursor<String> page = this.valkey.async()
                .scan(cursor, ScanArgs.Builder.matches(pattern).limit(500))
                .get(RESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            keys.addAll(page.getKeys());
            cursor = page;
        } while (!cursor.isFinished());
        return keys;
    }

    /**
     * The later of two instants.
     *
     * @param first First
     * @param second Second
     * @return Later instant
     */
    private static Instant later(final Instant first, final Instant second) {
        return first.isAfter(second) ? first : second;
    }

    /**
     * Handles remote cache invalidation messages for revocations.
     * When another Pantera node calls revokeJti/revokeUser, this handler
     * receives the pub/sub message and updates the local caches on this node
     * with the sender's revocation instant and expiry.
     */
    private final class RevocationCacheHandler implements Cleanable<String> {

        @Override
        public void invalidate(final String key) {
            RevocationMessage.decode(
                key, Instant.now(), ValkeyRevocationBlocklist.this.defaultTtlSeconds
            ).ifPresent(msg -> {
                if (msg.user()) {
                    ValkeyRevocationBlocklist.this.userCache.merge(
                        msg.subject(),
                        new UserRevocation(msg.revokedAt(), msg.expiresAt()),
                        UserRevocation::merge
                    );
                } else {
                    ValkeyRevocationBlocklist.this.jtiCache.merge(
                        msg.subject(), msg.expiresAt(), ValkeyRevocationBlocklist::later
                    );
                }
            });
        }

        @Override
        public void invalidateAll() {
            ValkeyRevocationBlocklist.this.jtiCache.clear();
            ValkeyRevocationBlocklist.this.userCache.clear();
        }
    }
}
