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
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RevocationMessage} — B47: the cross-node revocation
 * message carried no timestamp, so a peer used its receipt time and a fixed
 * 2-hour TTL and forgot a 7-day revocation after 2 hours.
 *
 * @since 2.2.9
 */
final class RevocationMessageTest {

    private static final Instant REVOKED = Instant.parse("2026-09-23T10:00:00.123Z");

    private static final Instant EXPIRES = REVOKED.plusSeconds(7 * 24 * 3600);

    private static final Instant RECEIVED = REVOKED.plusSeconds(1);

    @Test
    void userRevocationCarriesTheSendersInstantAndExpiry() {
        final RevocationMessage sent = new RevocationMessage(true, "a:b|c", REVOKED, EXPIRES);
        MatcherAssert.assertThat(
            RevocationMessage.decode(sent.encode(), RECEIVED, 7200),
            new IsEqual<>(Optional.of(sent))
        );
    }

    @Test
    void jtiRevocationCarriesTheSendersExpiry() {
        final RevocationMessage sent = new RevocationMessage(false, "jti-1", EXPIRES, EXPIRES);
        MatcherAssert.assertThat(
            RevocationMessage.decode(sent.encode(), RECEIVED, 7200)
                .map(RevocationMessage::expiresAt),
            new IsEqual<>(Optional.of(EXPIRES))
        );
    }

    @Test
    void legacyUserMessageFallsBackToReceiptTimeAndDefaultTtl() {
        MatcherAssert.assertThat(
            RevocationMessage.decode("user:alice", RECEIVED, 7200),
            new IsEqual<>(Optional.of(
                new RevocationMessage(true, "alice", RECEIVED, RECEIVED.plusSeconds(7200))
            ))
        );
    }

    @Test
    void garbageIsIgnored() {
        MatcherAssert.assertThat(
            RevocationMessage.decode("userat:x:y:alice", RECEIVED, 7200),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void legacyEncodingIsWhatPreUpgradeNodesDecode() {
        MatcherAssert.assertThat(
            "a 2.2.8 node matches 'user:' + username",
            new RevocationMessage(true, "alice", REVOKED, EXPIRES).encodeLegacy(),
            new IsEqual<>("user:alice")
        );
        MatcherAssert.assertThat(
            "a 2.2.8 node matches 'jti:' + jti",
            new RevocationMessage(false, "jti-1", EXPIRES, EXPIRES).encodeLegacy(),
            new IsEqual<>("jti:jti-1")
        );
    }

    @Test
    void storedUserValueInEpochMillisIsTheRevocationInstant() {
        MatcherAssert.assertThat(
            RevocationMessage.storedRevokedAt(
                Long.toString(REVOKED.toEpochMilli()), RECEIVED
            ),
            new IsEqual<>(REVOKED)
        );
    }

    @Test
    void storedUserValueInEpochSecondsIsTheRevocationSecond() {
        MatcherAssert.assertThat(
            RevocationMessage.storedRevokedAt(
                Long.toString(REVOKED.getEpochSecond()), RECEIVED
            ),
            new IsEqual<>(Instant.ofEpochSecond(REVOKED.getEpochSecond()))
        );
    }

    @Test
    void storedPreUpgradeMarkerRevokesUpToTheRestore() {
        MatcherAssert.assertThat(
            "a 2.2.8 node stored '1': the instant is unknown, so every token"
                + " issued before the restore stays revoked",
            RevocationMessage.storedRevokedAt("1", RECEIVED),
            new IsEqual<>(RECEIVED)
        );
    }
}
