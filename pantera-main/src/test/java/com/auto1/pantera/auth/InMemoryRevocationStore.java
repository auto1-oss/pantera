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
import com.auto1.pantera.db.dao.RevocationStore;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * In-memory {@link RevocationStore} fake for deterministic, Docker-free
 * unit tests of {@link ValkeyRevocationBlocklist} (WS2.1, 2.3.0) —
 * simulates the {@code revocation_blocklist} table two "node" instances can
 * share, exactly like both pointing at one shared Postgres would.
 *
 * @since 2.3.0
 */
final class InMemoryRevocationStore implements RevocationStore {

    /**
     * Backing rows, insertion order preserved (matches {@code ORDER BY
     * created_at ASC} in the real query).
     */
    private final List<TimestampedEntry> rows = new CopyOnWriteArrayList<>();

    /**
     * Number of {@link #insert} calls — lets tests assert the DB write
     * happened without inspecting row contents.
     */
    private final AtomicInteger insertCalls = new AtomicInteger();

    @Override
    public void insert(final String entryType, final String entryValue, final int ttlSeconds) {
        this.insertCalls.incrementAndGet();
        this.rows.add(
            new TimestampedEntry(
                Instant.now(),
                new RevocationDao.RevocationEntry(
                    entryType, entryValue, Instant.now().plusSeconds(ttlSeconds)
                )
            )
        );
    }

    @Override
    public List<RevocationDao.RevocationEntry> pollSince(final Instant since) {
        final Instant now = Instant.now();
        return this.rows.stream()
            .filter(row -> row.createdAt().isAfter(since))
            .filter(row -> row.entry().expiresAt().isAfter(now))
            .map(TimestampedEntry::entry)
            .collect(Collectors.toList());
    }

    /**
     * @return Number of {@link #insert} calls so far
     */
    int insertCalls() {
        return this.insertCalls.get();
    }

    /**
     * A row plus its insertion instant (the real table's {@code created_at},
     * which {@link RevocationDao.RevocationEntry} itself does not carry).
     * @param createdAt Insertion instant
     * @param entry The revocation entry
     */
    private record TimestampedEntry(Instant createdAt, RevocationDao.RevocationEntry entry) { }
}
