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
package com.auto1.pantera.auth.signin;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Says what is known of a credential that has the form of an API key and
 * was rejected.
 *
 * <p>The signature is checked apart from the expiry date: an expired or
 * revoked key still proves whose key it was. A key this installation did
 * not sign (a key of another installation, a made-up one) proves nothing,
 * and is reported under the user name it came with.</p>
 *
 * <p>Called only for a key that was already rejected, so it decides
 * nothing: it explains.</p>
 *
 * @since 2.2.15
 */
public final class KeyInspector {

    /**
     * Type claim of an API key.
     */
    private static final String API = "api";

    /**
     * Signature check with the public key of this installation.
     */
    private final Algorithm algorithm;

    /**
     * Whether a key id is stored and usable for an owner; null without a
     * database.
     */
    private final BiPredicate<String, String> stored;

    /**
     * Time.
     */
    private final Supplier<Instant> clock;

    /**
     * Ctor.
     *
     * @param key Public key of this installation
     * @param stored Whether a key id is stored and usable for an owner;
     *  null when keys are not stored
     */
    public KeyInspector(final RSAPublicKey key, final BiPredicate<String, String> stored) {
        this(key, stored, Instant::now);
    }

    /**
     * Ctor.
     *
     * @param key Public key of this installation
     * @param stored Whether a key id is stored and usable for an owner;
     *  null when keys are not stored
     * @param clock Time
     */
    KeyInspector(
        final RSAPublicKey key, final BiPredicate<String, String> stored,
        final Supplier<Instant> clock
    ) {
        this.algorithm = Algorithm.RSA256(key, null);
        this.stored = stored;
        this.clock = clock;
    }

    /**
     * Inspect a rejected credential.
     *
     * @param token The credential
     * @param sent User name it was sent with, empty when there was none
     * @return What is known; empty for a token that is no API key (a
     *  session or refresh token of this installation)
     */
    public Optional<RejectedKey> inspect(final String token, final String sent) {
        final String name;
        if (sent == null) {
            name = "";
        } else {
            name = sent;
        }
        Optional<RejectedKey> res;
        try {
            final DecodedJWT jwt = JWT.decode(token);
            if (this.signed(jwt)) {
                res = this.own(jwt);
            } else {
                final String claimed;
                if (name.isEmpty() && jwt.getSubject() != null) {
                    claimed = jwt.getSubject();
                } else {
                    claimed = name;
                }
                res = Optional.of(new RejectedKey(RejectedKey.UNKNOWN, claimed, "", false));
            }
        } catch (final JWTVerificationException malformed) {
            res = Optional.of(new RejectedKey(RejectedKey.UNKNOWN, name, "", false));
        }
        return res;
    }

    private boolean signed(final DecodedJWT jwt) {
        boolean signed;
        try {
            this.algorithm.verify(jwt);
            signed = true;
        } catch (final JWTVerificationException | IllegalArgumentException bad) {
            signed = false;
        }
        return signed;
    }

    /**
     * A token this installation signed.
     *
     * @param jwt The token
     * @return Why an API key was rejected; empty for another type of token
     */
    private Optional<RejectedKey> own(final DecodedJWT jwt) {
        final Optional<RejectedKey> res;
        final String owner = jwt.getSubject();
        final String jti = jwt.getId();
        if (!KeyInspector.API.equals(jwt.getClaim("type").asString())
            || owner == null || jti == null) {
            res = Optional.empty();
        } else if (jwt.getExpiresAtAsInstant() != null
            && jwt.getExpiresAtAsInstant().isBefore(this.clock.get())) {
            res = Optional.of(new RejectedKey(RejectedKey.EXPIRED, owner, jti, true));
        } else if (this.stored != null && !this.usable(jti, owner)) {
            res = Optional.of(new RejectedKey(RejectedKey.REVOKED, owner, jti, true));
        } else {
            res = Optional.of(new RejectedKey(RejectedKey.REFUSED, owner, jti, true));
        }
        return res;
    }

    private boolean usable(final String jti, final String owner) {
        boolean usable;
        try {
            usable = this.stored.test(jti, owner);
        } catch (final RuntimeException err) {
            // The store cannot be asked: say nothing it did not say.
            usable = true;
        }
        return usable;
    }
}
