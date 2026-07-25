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
import com.auto1.pantera.cache.ValkeyConnection;
import java.time.Duration;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link ValkeyRevocationBlocklist} against a real
 * Valkey pub/sub channel (Testcontainers). The DB side uses
 * {@link InMemoryRevocationStore} — real Postgres adds nothing here since
 * {@link com.auto1.pantera.db.dao.RevocationDao} is exercised on its own in
 * {@link DbRevocationBlocklistTest}; what this class needs covered against a
 * genuine Valkey server is the pub/sub fan-out.
 *
 * @since 2.1.0
 */
@Testcontainers
final class ValkeyRevocationBlocklistTest {

    /**
     * Valkey container.
     */
    @Container
    @SuppressWarnings("rawtypes")
    static final GenericContainer VALKEY =
        new GenericContainer<>("valkey/valkey:8.1.4")
            .withExposedPorts(6379);

    /**
     * Valkey connection.
     */
    private ValkeyConnection conn;

    /**
     * Pub/sub invalidation channel.
     */
    private CacheInvalidationPubSub pubSub;

    /**
     * In-memory DB fake — source of truth.
     */
    private InMemoryRevocationStore store;

    /**
     * Blocklist under test.
     */
    private ValkeyRevocationBlocklist blocklist;

    @BeforeEach
    void setUp() {
        final String host = VALKEY.getHost();
        final int port = VALKEY.getMappedPort(6379);
        this.conn = new ValkeyConnection(host, port, Duration.ofSeconds(5));
        this.pubSub = new CacheInvalidationPubSub(this.conn);
        this.store = new InMemoryRevocationStore();
        this.blocklist = new ValkeyRevocationBlocklist(this.pubSub, this.store, 3600);
    }

    @AfterEach
    void tearDown() {
        if (this.pubSub != null) {
            this.pubSub.close();
        }
        if (this.conn != null) {
            this.conn.close();
        }
    }

    @Test
    void jtiNotRevokedByDefault() {
        MatcherAssert.assertThat(
            "Fresh blocklist must not report any JTI as revoked",
            this.blocklist.isRevokedJti("some-jti-value"),
            Matchers.is(false)
        );
    }

    @Test
    void revokesAndChecksJti() {
        this.blocklist.revokeJti("test-jti-123", 3600);
        MatcherAssert.assertThat(
            "Revoked JTI must be reported as revoked",
            this.blocklist.isRevokedJti("test-jti-123"),
            Matchers.is(true)
        );
    }

    @Test
    void revokesAndChecksUser() {
        this.blocklist.revokeUser("alice", 3600);
        MatcherAssert.assertThat(
            "Revoked user must be reported as revoked",
            this.blocklist.isRevokedUser("alice"),
            Matchers.is(true)
        );
    }

    @Test
    void unrevokedUserNotBlocked() {
        this.blocklist.revokeUser("bob", 3600);
        MatcherAssert.assertThat(
            "Non-revoked user must not be reported as revoked",
            this.blocklist.isRevokedUser("carol"),
            Matchers.is(false)
        );
    }

    @Test
    void revokeWritesTheDbRowFirst() {
        this.blocklist.revokeJti("db-durable-jti", 3600);
        MatcherAssert.assertThat(
            "Revocation must be DB-durable — insert() called once",
            this.store.insertCalls(),
            Matchers.is(1)
        );
    }

    @Test
    void peerOnAGenuineSecondValkeyConnectionObservesTheRevocation() {
        // A real second "node": its own ValkeyConnection + pub/sub instance
        // (distinct instanceId) pointed at the same Valkey server, its own
        // DB fake. Sharing one CacheInvalidationPubSub between two
        // blocklists would self-filter every message (same instanceId) and
        // wouldn't prove anything about cross-node fan-out.
        try (ValkeyConnection peerConn =
            new ValkeyConnection(VALKEY.getHost(), VALKEY.getMappedPort(6379), Duration.ofSeconds(5));
            CacheInvalidationPubSub peerPubSub = new CacheInvalidationPubSub(peerConn)) {
            final ValkeyRevocationBlocklist peer = new ValkeyRevocationBlocklist(
                peerPubSub, new InMemoryRevocationStore(), 3600
            );
            this.blocklist.revokeJti("cross-node-jti", 7200);
            org.awaitility.Awaitility.await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
                .untilAsserted(
                    () -> MatcherAssert.assertThat(
                        "Peer must observe the revocation via real Valkey pub/sub",
                        peer.isRevokedJti("cross-node-jti"),
                        Matchers.is(true)
                    )
                );
        }
    }
}
