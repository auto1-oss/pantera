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

import com.auto1.pantera.db.dao.RevocationDao;
import com.auto1.pantera.http.log.EcsLogger;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB-backed revocation blocklist with local in-memory cache.
 *
 * <p>Polls the {@code revocation_blocklist} table every 5 seconds to refresh
 * the local cache. On every check, if the cache is stale (older than 5 s),
 * a lightweight poll query fetches only entries created since the last poll.
 * On revoke, the entry is written to the DB immediately and the local cache
 * is updated synchronously so the calling node rejects the token without
 * waiting for the next poll cycle.
 *
 * @since 2.1.0
 */
public final class DbRevocationBlocklist implements RevocationBlocklist {

    /**
     * Poll interval: 5 seconds.
     */
    private static final long POLL_INTERVAL_MS = 5_000L;

    /**
     * Entry type constant for JTI-based revocations.
     */
    private static final String TYPE_JTI = "jti";

    /**
     * Entry type constant for user-based revocations.
     */
    private static final String TYPE_USER = "username";

    /**
     * Underlying DAO for DB access.
     */
    private final RevocationDao dao;

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
     * Ctor.
     * @param dao Revocation DAO for DB access
     */
    public DbRevocationBlocklist(final RevocationDao dao) {
        this.dao = dao;
        this.jtiCache = new ConcurrentHashMap<>();
        this.userCache = new ConcurrentHashMap<>();
        this.lastPoll = Instant.EPOCH;
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
    }

    @Override
    public void revokeUser(final String username, final int ttlSeconds) {
        this.dao.insert(TYPE_USER, username, ttlSeconds);
        this.userCache.put(username, Instant.now().plusSeconds(ttlSeconds));
    }

    /**
     * Poll the DB if more than {@link #POLL_INTERVAL_MS} ms have elapsed since the last poll.
     * Fetches only entries created after {@link #lastPoll} so the query stays lightweight.
     */
    private void pollIfStale() {
        final Instant now = Instant.now();
        if (now.toEpochMilli() - this.lastPoll.toEpochMilli() < POLL_INTERVAL_MS) {
            return;
        }
        final Instant pollFrom = this.lastPoll;
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
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.auth.DbRevocationBlocklist")
                .message("Failed to poll revocation blocklist from DB")
                .error(ex)
                .log();
        }
    }
}
