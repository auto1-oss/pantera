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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SessionRevoker} — the single "a credential changed, drop
 * every live token" primitive shared by password change/reset and user
 * disable (SecOps token-revocation #38).
 *
 * <p>Before 2.2.9 only {@code disableUser} revoked tokens; a password change
 * or admin reset left every existing session and API token authorizing, so
 * rotating a compromised password did not evict the attacker.</p>
 *
 * @since 2.2.9
 */
final class SessionRevokerTest {

    @Test
    void revokesBlocklistAndPersistedTokens() {
        final RecordingBlocklist blocklist = new RecordingBlocklist();
        final InMemoryUserTokenDao dao = new InMemoryUserTokenDao();
        final UUID api = UUID.randomUUID();
        final UUID refresh = UUID.randomUUID();
        dao.store(api, "alice", "ci", "t", Instant.now().plusSeconds(60), "api");
        dao.store(refresh, "alice", "r", "t", Instant.now().plusSeconds(60), "refresh");
        dao.store(UUID.randomUUID(), "bob", "ci", "t", Instant.now().plusSeconds(60), "api");

        final int revoked = new SessionRevoker(blocklist, dao).revokeAll("alice");

        MatcherAssert.assertThat(
            "both of alice's persisted tokens must be revoked",
            revoked, new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "alice's API token must be revoked",
            dao.revoked(api), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "alice's refresh token must be revoked",
            dao.revoked(refresh), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the user-wide access-token blocklist entry must be broadcast",
            blocklist.revokedUsers, new IsEqual<>(List.of("alice"))
        );
        MatcherAssert.assertThat(
            "the blocklist window must cover the default refresh TTL (7 days)",
            blocklist.lastTtl, new IsEqual<>(7 * 24 * 3600)
        );
    }

    @Test
    void toleratesUnwiredCollaborators() {
        MatcherAssert.assertThat(
            "no blocklist and no DAO (no-DB boot) must be a safe no-op",
            new SessionRevoker(null, null).revokeAll("alice"), new IsEqual<>(0)
        );
    }

    /**
     * Blocklist test double that records user-wide revocations.
     */
    private static final class RecordingBlocklist implements RevocationBlocklist {
        private final List<String> revokedUsers = new CopyOnWriteArrayList<>();
        private int lastTtl;

        @Override
        public boolean isRevokedJti(final String jti) {
            return false;
        }

        @Override
        public boolean isRevokedUser(final String username) {
            return this.revokedUsers.contains(username);
        }

        @Override
        public void revokeJti(final String jti, final int ttlSeconds) {
            // not exercised
        }

        @Override
        public void revokeUser(final String username, final int ttlSeconds) {
            this.revokedUsers.add(username);
            this.lastTtl = ttlSeconds;
        }
    }
}
