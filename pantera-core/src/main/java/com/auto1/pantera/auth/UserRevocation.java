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
import java.time.temporal.ChronoUnit;

/**
 * A user-wide revocation: every token issued before {@code revokedAt} is
 * revoked until {@code expiresAt}. Tokens issued afterwards — the user's
 * fresh login after a password change or an admin "revoke sessions" — are
 * not affected.
 *
 * <p>JWT {@code iat} has one-second resolution, so the comparison is made
 * at whole seconds: a token issued in the same second as the revocation is
 * treated as issued after it. Rejecting it instead would lock out a client
 * that re-authenticates immediately (the UI does, after a password change).</p>
 *
 * @param revokedAt When the revocation was issued
 * @param expiresAt When the entry lapses (tokens older than that are expired anyway)
 * @since 2.2.9
 */
public record UserRevocation(Instant revokedAt, Instant expiresAt) {

    /**
     * Whether a token issued at {@code issuedAt} is revoked by this entry.
     *
     * @param issuedAt Token {@code iat}; {@code null} (no claim) counts as revoked
     * @param now Current time
     * @return True if the token must be rejected
     */
    public boolean revokes(final Instant issuedAt, final Instant now) {
        if (now.isAfter(this.expiresAt)) {
            return false;
        }
        return issuedAt == null
            || issuedAt.isBefore(this.revokedAt.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * Whether the entry has lapsed and can be dropped.
     *
     * @param now Current time
     * @return True once {@code expiresAt} has passed
     */
    public boolean expired(final Instant now) {
        return now.isAfter(this.expiresAt);
    }

    /**
     * Combine with another revocation for the same user: the later
     * revocation instant and the later expiry win.
     *
     * @param other Another revocation, may be {@code null}
     * @return The merged revocation
     */
    public UserRevocation merge(final UserRevocation other) {
        if (other == null) {
            return this;
        }
        return new UserRevocation(
            this.revokedAt.isAfter(other.revokedAt) ? this.revokedAt : other.revokedAt,
            this.expiresAt.isAfter(other.expiresAt) ? this.expiresAt : other.expiresAt
        );
    }
}
