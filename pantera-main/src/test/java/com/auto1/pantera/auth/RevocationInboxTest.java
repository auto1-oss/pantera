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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RevocationInbox} — B47 rolling upgrade: an upgraded node
 * publishes each revocation twice (current form, then the pre-2.2.9 form so
 * nodes not yet upgraded still receive it). An upgraded peer must apply the
 * current form and drop the legacy echo, which would otherwise stamp the
 * revocation with the peer's receipt time and a default TTL.
 *
 * @since 2.2.9
 */
final class RevocationInboxTest {

    private static final Instant REVOKED = Instant.parse("2026-09-23T10:00:00.123Z");

    private static final Instant EXPIRES = REVOKED.plusSeconds(7 * 24 * 3600);

    private static final Instant RECEIVED = REVOKED.plusMillis(40);

    @Test
    void legacyEchoOfACurrentUserMessageIsDropped() {
        final RevocationInbox inbox = new RevocationInbox(Duration.ofMinutes(1));
        final RevocationMessage sent = new RevocationMessage(true, "alice", REVOKED, EXPIRES);
        MatcherAssert.assertThat(
            "the current form is applied with the sender's instant",
            inbox.accept(sent.encode(), RECEIVED, 7200),
            new IsEqual<>(Optional.of(sent))
        );
        MatcherAssert.assertThat(
            "the legacy echo that follows it is dropped",
            inbox.accept(sent.encodeLegacy(), RECEIVED, 7200),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void legacyEchoOfACurrentJtiMessageIsDropped() {
        final RevocationInbox inbox = new RevocationInbox(Duration.ofMinutes(1));
        final RevocationMessage sent = new RevocationMessage(false, "jti-1", EXPIRES, EXPIRES);
        inbox.accept(sent.encode(), RECEIVED, 7200);
        MatcherAssert.assertThat(
            inbox.accept(sent.encodeLegacy(), RECEIVED, 7200),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void legacyMessageFromAPreUpgradeNodeIsApplied() {
        MatcherAssert.assertThat(
            new RevocationInbox(Duration.ofMinutes(1)).accept("user:bob", RECEIVED, 7200),
            new IsEqual<>(Optional.of(
                new RevocationMessage(true, "bob", RECEIVED, RECEIVED.plusSeconds(7200))
            ))
        );
    }

    @Test
    void currentMessageForAnotherSubjectDoesNotSuppressALegacyOne() {
        final RevocationInbox inbox = new RevocationInbox(Duration.ofMinutes(1));
        inbox.accept(
            new RevocationMessage(true, "alice", REVOKED, EXPIRES).encode(), RECEIVED, 7200
        );
        MatcherAssert.assertThat(
            "a JTI equal to a username is a different subject",
            inbox.accept("jti:alice", RECEIVED, 7200).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "another user's legacy revocation still applies",
            inbox.accept("user:carol", RECEIVED, 7200).isPresent(),
            new IsEqual<>(true)
        );
    }
}
