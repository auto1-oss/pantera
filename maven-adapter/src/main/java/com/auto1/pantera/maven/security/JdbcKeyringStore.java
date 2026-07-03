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
package com.auto1.pantera.maven.security;

import com.auto1.pantera.http.log.EcsLogger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.bouncycastle.openpgp.PGPPublicKey;

/**
 * JDBC-backed {@link KeyringStore} reading admin-uploaded ASCII-armored
 * public keys from the {@code pgp_keyring} table (V131).
 *
 * <p>Lookups are L1-cached in a small Caffeine cache (TTL 5 min, 256
 * entries) so repeated verifications of the same artifact don't hit
 * the DB. The cache stores both hits and misses to avoid hammering the
 * DB with retries when a key is genuinely absent.
 *
 * @since 2.2.0
 */
public final class JdbcKeyringStore implements KeyringStore {

    /** Logger name for warnings on lookup failure. */
    private static final String LOGGER = "com.auto1.pantera.maven.security";

    /** Long-key-id is stored hex-encoded so the SQL stays type-stable. */
    private static final String LOOKUP =
        "SELECT public_key_armored FROM pgp_keyring WHERE key_id_hex = ?";

    /** Underlying connection pool. */
    private final DataSource source;

    /** L1 cache — keyed by long key id; absent entries stored as empty. */
    private final Cache<Long, Optional<PGPPublicKey>> cache;

    /**
     * Ctor.
     *
     * @param source Connection pool
     */
    public JdbcKeyringStore(final DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
        this.cache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
    }

    @Override
    public Optional<PGPPublicKey> findByKeyId(final long keyId) {
        return this.cache.get(keyId, this::loadFromDb);
    }

    /**
     * Force-evict a key from the L1 cache. Called by the admin REST
     * endpoint after an upload / delete so the next verification picks
     * up the change without waiting for the TTL.
     *
     * @param keyId Long key id (signed 64-bit form)
     */
    public void invalidate(final long keyId) {
        this.cache.invalidate(keyId);
    }

    /** Drop every cached entry — used by tests / hot-reload paths. */
    public void invalidateAll() {
        this.cache.invalidateAll();
    }

    /**
     * Look the key up in the DB. Returns {@link Optional#empty()} when
     * the row is absent OR when SQL / parsing fails — failures are logged
     * at WARN and treated as "untrusted" by the caller, which is the
     * conservative behaviour for signature verification.
     *
     * @param keyId Long key id
     * @return Parsed public key when present and parseable
     */
    private Optional<PGPPublicKey> loadFromDb(final long keyId) {
        final String hex = String.format(
            Locale.ROOT, "%016X", keyId
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement stmt = conn.prepareStatement(LOOKUP)) {
            stmt.setString(1, hex);
            try (ResultSet rows = stmt.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                final String armored = rows.getString(1);
                if (armored == null || armored.isBlank()) {
                    return Optional.empty();
                }
                final InMemoryKeyringStore tmp = new InMemoryKeyringStore();
                tmp.addAsciiArmored(armored.getBytes());
                return tmp.findByKeyId(keyId);
            }
        } catch (final SQLException | java.io.IOException
                | org.bouncycastle.openpgp.PGPException ex) {
            EcsLogger.warn(LOGGER)
                .message("Failed to load PGP key from DB key_id_hex=" + hex)
                .eventCategory("database")
                .eventAction("pgp_key_lookup")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
            return Optional.empty();
        }
    }
}
