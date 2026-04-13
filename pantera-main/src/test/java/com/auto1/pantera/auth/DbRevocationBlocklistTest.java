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

import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.db.dao.RevocationDao;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link DbRevocationBlocklist}.
 * @since 2.1.0
 */
@Testcontainers
final class DbRevocationBlocklistTest {

    /**
     * PostgreSQL test container.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    /**
     * Data source for tests.
     */
    private DataSource source;

    /**
     * Blocklist under test.
     */
    private DbRevocationBlocklist blocklist;

    @BeforeEach
    void setUp() throws Exception {
        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(5);
        config.setPoolName("DbRevocationBlocklistTest-Pool");
        this.source = new HikariDataSource(config);
        try (Connection conn = this.source.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP TABLE IF EXISTS revocation_blocklist");
            stmt.executeUpdate(
                String.join(
                    "\n",
                    "CREATE TABLE IF NOT EXISTS revocation_blocklist (",
                    "    id          BIGSERIAL PRIMARY KEY,",
                    "    entry_type  VARCHAR(10) NOT NULL,",
                    "    entry_value VARCHAR(255) NOT NULL,",
                    "    expires_at  TIMESTAMPTZ NOT NULL,",
                    "    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()",
                    ")"
                )
            );
        }
        this.blocklist = new DbRevocationBlocklist(new RevocationDao(this.source));
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
