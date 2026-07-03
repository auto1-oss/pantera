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
 * Integration tests for {@link ValkeyRevocationBlocklist}.
 * Uses a Testcontainers Valkey container.
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
     * Blocklist under test.
     */
    private ValkeyRevocationBlocklist blocklist;

    @BeforeEach
    void setUp() {
        final String host = VALKEY.getHost();
        final int port = VALKEY.getMappedPort(6379);
        this.conn = new ValkeyConnection(host, port, Duration.ofSeconds(5));
        this.pubSub = new CacheInvalidationPubSub(this.conn);
        this.blocklist = new ValkeyRevocationBlocklist(this.conn, this.pubSub, 3600);
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
}
