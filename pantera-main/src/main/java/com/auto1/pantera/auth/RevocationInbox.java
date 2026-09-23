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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Receives revocation messages from peers.
 *
 * <p>During a rolling upgrade an upgraded node publishes each revocation
 * twice: the current form carrying the sender's instant and expiry, then
 * the pre-2.2.9 form so nodes not yet upgraded still apply it (B47). An
 * upgraded receiver applies the current form and drops the legacy echo that
 * follows it, which would otherwise re-stamp the revocation with this
 * node's receipt time and a default TTL. A legacy message with no current
 * form ahead of it comes from a node not yet upgraded and is applied.</p>
 *
 * <p>Each current-form subject suppresses at most one legacy message, so a
 * later genuine revocation from a node not yet upgraded is still applied.
 * All operations are in-memory and non-blocking.</p>
 *
 * @since 2.2.9
 */
final class RevocationInbox {

    /**
     * Upper bound on subjects awaiting their legacy echo.
     */
    private static final long MAX_PENDING = 10_000L;

    /**
     * Subjects heard in the current form whose legacy echo has not arrived.
     */
    private final Cache<String, Boolean> pending;

    /**
     * Ctor.
     *
     * @param window How long a current-form message suppresses its echo
     */
    RevocationInbox(final Duration window) {
        this.pending = Caffeine.newBuilder()
            .expireAfterWrite(window)
            .maximumSize(MAX_PENDING)
            .build();
    }

    /**
     * Decode a peer message, dropping the legacy echo of a current one.
     *
     * @param raw Message payload
     * @param now Receipt time
     * @param defaultTtlSeconds Expiry for messages from nodes not yet upgraded
     * @return Revocation to apply, or empty
     */
    Optional<RevocationMessage> accept(
        final String raw, final Instant now, final int defaultTtlSeconds
    ) {
        final Optional<RevocationMessage> msg =
            RevocationMessage.decode(raw, now, defaultTtlSeconds);
        final Optional<RevocationMessage> result;
        if (msg.isEmpty()) {
            result = msg;
        } else if (RevocationMessage.legacy(raw)) {
            if (this.pending.asMap().remove(RevocationInbox.key(msg.get())) == null) {
                result = msg;
            } else {
                result = Optional.empty();
            }
        } else {
            this.pending.put(RevocationInbox.key(msg.get()), Boolean.TRUE);
            result = msg;
        }
        return result;
    }

    /**
     * Subject key, distinct for users and JTIs.
     *
     * @param msg Message
     * @return Key
     */
    private static String key(final RevocationMessage msg) {
        final String kind;
        if (msg.user()) {
            kind = "u:";
        } else {
            kind = "j:";
        }
        return kind + msg.subject();
    }
}
