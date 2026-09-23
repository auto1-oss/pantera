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

/**
 * Cross-node revocation message carried on the {@code revocation} pub/sub
 * channel.
 *
 * <p>Since 2.2.9 the message carries the sender's revocation instant and
 * expiry ({@code userat:<revokedMs>:<expiresMs>:<username>},
 * {@code jtiexp:<expiresMs>:<jti>}). The earlier form ({@code user:<name>},
 * {@code jti:<jti>}) carried neither, so a peer substituted its receipt time
 * and a fixed default TTL — forgetting a 7-day revocation after 2 hours
 * (B47). The earlier form is still decoded (receipt time + default TTL) so
 * a rolling upgrade keeps propagating revocations from older nodes, and
 * every revocation is also published in the earlier form
 * ({@link #encodeLegacy()}) so nodes not yet upgraded still receive it;
 * upgraded peers drop that echo ({@link RevocationInbox}).</p>
 *
 * @param user True for a user-wide revocation, false for a single JTI
 * @param subject Username or JTI
 * @param revokedAt Revocation instant (user revocations)
 * @param expiresAt When the entry lapses
 * @since 2.2.9
 */
record RevocationMessage(boolean user, String subject, Instant revokedAt, Instant expiresAt) {

    /**
     * User revocation with epoch fields.
     */
    private static final String USER_AT = "userat:";

    /**
     * JTI revocation with an expiry field.
     */
    private static final String JTI_EXP = "jtiexp:";

    /**
     * Legacy user revocation (no timestamps).
     */
    private static final String USER_LEGACY = "user:";

    /**
     * Legacy JTI revocation (no expiry).
     */
    private static final String JTI_LEGACY = "jti:";

    /**
     * Stored values below this are epoch seconds rather than milliseconds.
     */
    private static final long MILLIS_THRESHOLD = 100_000_000_000L;

    /**
     * Stored values below this (2001-09-09 in epoch seconds) are not an
     * instant at all but the pre-2.2.9 marker {@code 1}.
     */
    private static final long MIN_EPOCH_SECONDS = 1_000_000_000L;

    /**
     * Wire form of this message.
     * @return Encoded message
     */
    String encode() {
        if (this.user) {
            return USER_AT + this.revokedAt.toEpochMilli() + ':'
                + this.expiresAt.toEpochMilli() + ':' + this.subject;
        }
        return JTI_EXP + this.expiresAt.toEpochMilli() + ':' + this.subject;
    }

    /**
     * Pre-2.2.9 wire form of this message ({@code user:<name>} /
     * {@code jti:<jti>}), the only form a node not yet upgraded decodes.
     * @return Encoded legacy message
     */
    String encodeLegacy() {
        if (this.user) {
            return USER_LEGACY + this.subject;
        }
        return JTI_LEGACY + this.subject;
    }

    /**
     * Whether this message arrived in the pre-2.2.9 wire form.
     * @param raw Message payload
     * @return True for {@code user:} / {@code jti:} messages
     */
    static boolean legacy(final String raw) {
        return raw.startsWith(USER_LEGACY) || raw.startsWith(JTI_LEGACY);
    }

    /**
     * Revocation instant held in a {@code pantera:revoked:user:*} Valkey
     * value. Since 2.2.9 the value is epoch milliseconds; an earlier 2.2.9
     * build stored epoch seconds; 2.2.8 and older stored the marker
     * {@code 1}, which carries no instant — then every token issued before
     * {@code now} is treated as revoked, which errs toward revoking as the
     * old node itself does.
     * @param raw Stored value
     * @param now Restore time
     * @return Revocation instant
     * @throws NumberFormatException When the value is not a number
     */
    static Instant storedRevokedAt(final String raw, final Instant now) {
        final long stored = Long.parseLong(raw.trim());
        final Instant result;
        if (stored < MIN_EPOCH_SECONDS) {
            result = now;
        } else if (stored < MILLIS_THRESHOLD) {
            result = Instant.ofEpochSecond(stored);
        } else {
            result = Instant.ofEpochMilli(stored);
        }
        return result;
    }

    /**
     * Decode a wire message.
     * @param raw Message payload
     * @param now Receipt time (legacy messages only)
     * @param defaultTtlSeconds Expiry for legacy messages
     * @return Message, or empty when unrecognised
     */
    static Optional<RevocationMessage> decode(
        final String raw, final Instant now, final int defaultTtlSeconds
    ) {
        Optional<RevocationMessage> msg = Optional.empty();
        try {
            if (raw.startsWith(USER_AT)) {
                final String[] parts = raw.substring(USER_AT.length()).split(":", 3);
                if (parts.length == 3 && !parts[2].isEmpty()) {
                    msg = Optional.of(new RevocationMessage(
                        true, parts[2],
                        Instant.ofEpochMilli(Long.parseLong(parts[0])),
                        Instant.ofEpochMilli(Long.parseLong(parts[1]))
                    ));
                }
            } else if (raw.startsWith(JTI_EXP)) {
                final String[] parts = raw.substring(JTI_EXP.length()).split(":", 2);
                if (parts.length == 2 && !parts[1].isEmpty()) {
                    final Instant exp = Instant.ofEpochMilli(Long.parseLong(parts[0]));
                    msg = Optional.of(new RevocationMessage(false, parts[1], exp, exp));
                }
            } else if (raw.startsWith(USER_LEGACY)) {
                msg = Optional.of(new RevocationMessage(
                    true, raw.substring(USER_LEGACY.length()),
                    now, now.plusSeconds(defaultTtlSeconds)
                ));
            } else if (raw.startsWith(JTI_LEGACY)) {
                final Instant exp = now.plusSeconds(defaultTtlSeconds);
                msg = Optional.of(new RevocationMessage(
                    false, raw.substring(JTI_LEGACY.length()), exp, exp
                ));
            }
        } catch (final NumberFormatException ex) {
            msg = Optional.empty();
        }
        return msg;
    }
}
