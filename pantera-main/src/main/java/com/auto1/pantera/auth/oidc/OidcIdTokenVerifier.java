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
package com.auto1.pantera.auth.oidc;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;

/**
 * Cryptographic verification of an OIDC {@code id_token} — the relying-party
 * checks the browser-callback login flow skipped before 2.2.9.
 *
 * <p>A token is accepted only when ALL hold: it is RS256-signed by the
 * provider key named in its {@code kid} header (resolved through the
 * {@link JwkSource}; an unknown kid fails closed), {@code iss} equals the
 * configured issuer exactly, {@code aud} contains this client id,
 * {@code exp} is present and in the future (60 s leeway), and — when the
 * login issued a nonce — the {@code nonce} claim matches it, binding the
 * token to this browser's authorization request. Reuses the same Auth0
 * java-jwt verifier Pantera uses for its own RS256 tokens.</p>
 *
 * @since 2.2.9
 */
public final class OidcIdTokenVerifier {

    /**
     * Clock skew tolerance for {@code exp}/{@code iat}/{@code nbf}.
     */
    private static final long LEEWAY_SECONDS = 60L;

    /**
     * Provider signing keys.
     */
    private final JwkSource keys;

    /**
     * Expected {@code iss}.
     */
    private final String issuer;

    /**
     * Expected {@code aud} (this relying party's client id).
     */
    private final String clientId;

    /**
     * Ctor.
     *
     * @param keys Provider signing keys
     * @param issuer Exact expected issuer
     * @param clientId This client's id (expected audience)
     */
    public OidcIdTokenVerifier(final JwkSource keys, final String issuer, final String clientId) {
        this.keys = keys;
        this.issuer = issuer;
        this.clientId = clientId;
    }

    /**
     * Verify an id_token.
     *
     * @param idToken Compact JWS from the token endpoint
     * @param expectedNonce Nonce issued at redirect time; {@code null} skips
     *  the nonce check (only for flows that did not issue one)
     * @return The verified token, whose claims may now be trusted
     * @throws OidcVerificationException on any failed check
     */
    public DecodedJWT verify(final String idToken, final String expectedNonce) {
        final DecodedJWT unverified;
        try {
            unverified = JWT.decode(idToken);
        } catch (final JWTDecodeException ex) {
            throw new OidcVerificationException("id_token is not a well-formed JWT", ex);
        }
        if (!"RS256".equals(unverified.getAlgorithm())) {
            throw new OidcVerificationException(
                "id_token alg must be RS256, got " + unverified.getAlgorithm()
            );
        }
        final String kid = unverified.getKeyId();
        if (kid == null || kid.isBlank()) {
            throw new OidcVerificationException("id_token header has no kid");
        }
        final Optional<RSAPublicKey> key = this.keys.key(kid);
        if (key.isEmpty()) {
            throw new OidcVerificationException("no provider key for kid " + kid);
        }
        final DecodedJWT verified;
        try {
            verified = JWT.require(Algorithm.RSA256(key.get(), null))
                .withIssuer(this.issuer)
                .withAudience(this.clientId)
                .withClaimPresence("exp")
                .withClaimPresence("sub")
                .acceptLeeway(LEEWAY_SECONDS)
                .build()
                .verify(idToken);
        } catch (final JWTVerificationException ex) {
            throw new OidcVerificationException("id_token failed verification: " + ex.getMessage(), ex);
        }
        if (expectedNonce != null) {
            final String nonce = verified.getClaim("nonce").asString();
            if (nonce == null || !nonce.equals(expectedNonce)) {
                throw new OidcVerificationException("id_token nonce does not match this login");
            }
        }
        return verified;
    }
}
