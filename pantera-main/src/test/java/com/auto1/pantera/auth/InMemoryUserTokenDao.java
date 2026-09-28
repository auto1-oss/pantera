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

import com.auto1.pantera.db.dao.UserTokenDao;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link UserTokenDao} for unit tests: the {@code user_tokens}
 * table as a map, so token-lifecycle logic (JTI ownership, refresh
 * rotation, bulk revocation) can be exercised without a database.
 *
 * @since 2.2.9
 */
final class InMemoryUserTokenDao extends UserTokenDao {

    /**
     * Stored token row.
     */
    record Row(String username, String type, boolean revoked) {
    }

    private final Map<UUID, Row> rows = new ConcurrentHashMap<>();

    InMemoryUserTokenDao() {
        super(null);
    }

    @Override
    public void store(final UUID id, final String username, final String label,
        final String tokenValue, final Instant expiresAt, final String tokenType) {
        this.rows.put(id, new Row(username, tokenType, false));
    }

    @Override
    public boolean isValidForUser(final UUID id, final String username) {
        final Row row = this.rows.get(id);
        return row != null && row.username().equals(username) && !row.revoked();
    }

    @Override
    public boolean isValid(final UUID id) {
        final Row row = this.rows.get(id);
        return row != null && !row.revoked();
    }

    @Override
    public boolean consumeRefresh(final UUID id, final String username) {
        final Row row = this.rows.get(id);
        if (row == null || row.revoked() || !row.username().equals(username)
            || !"refresh".equals(row.type())) {
            return false;
        }
        this.rows.put(id, new Row(row.username(), row.type(), true));
        return true;
    }

    @Override
    public int revokeAllForUser(final String username) {
        int count = 0;
        for (final Map.Entry<UUID, Row> entry : this.rows.entrySet()) {
            final Row row = entry.getValue();
            if (row.username().equals(username) && !row.revoked()) {
                this.rows.put(entry.getKey(), new Row(row.username(), row.type(), true));
                count = count + 1;
            }
        }
        return count;
    }

    /**
     * Whether the JTI is stored and revoked.
     * @param id Token id
     * @return True if revoked
     */
    boolean revoked(final UUID id) {
        final Row row = this.rows.get(id);
        return row != null && row.revoked();
    }
}
