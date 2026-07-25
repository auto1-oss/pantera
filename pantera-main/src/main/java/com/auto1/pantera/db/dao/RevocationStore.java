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
package com.auto1.pantera.db.dao;

import java.time.Instant;
import java.util.List;

/**
 * DB-durable revocation persistence, implemented by {@link RevocationDao}.
 * <p>
 * Extracted so {@link com.auto1.pantera.auth.ValkeyRevocationBlocklist} (and
 * tests) can depend on this narrow contract instead of the concrete
 * JDBC-backed DAO — letting two-instance revocation-propagation tests use a
 * deterministic in-memory fake instead of a Testcontainers Postgres.
 *
 * @since 2.3.0
 */
public interface RevocationStore {

    /**
     * Insert a revocation entry with a TTL.
     * @param entryType Entry type: "jti" or "username"
     * @param entryValue The JTI string or username
     * @param ttlSeconds Time-to-live in seconds; expires_at = NOW() + ttl
     */
    void insert(String entryType, String entryValue, int ttlSeconds);

    /**
     * Poll for revocation entries created since a given timestamp that have
     * not yet expired.
     * @param since Fetch entries created after this instant
     * @return List of active revocation entries created since the given timestamp
     */
    List<RevocationDao.RevocationEntry> pollSince(Instant since);
}
