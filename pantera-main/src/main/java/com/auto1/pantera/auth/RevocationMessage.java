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
 * a rolling upgrade keeps propagating revocations from older nodes.</p>
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
