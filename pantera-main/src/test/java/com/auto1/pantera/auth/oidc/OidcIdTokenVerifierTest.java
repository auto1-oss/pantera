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
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for the OIDC id_token verifier (SecOps sso-oidc).
 *
 * <p>Before 2.2.9 the SSO callback base64-decoded the id_token PAYLOAD and
 * trusted its claims with no signature / issuer / audience / nonce / expiry
 * verification: anyone who could reach the callback with a self-minted
 * "id_token" naming an existing user logged in as that user. Every test
 * below is a forgery the old code accepted; the verifier must reject each
 * and accept only a token signed by the provider's key for this client,
 * bound to this login's nonce.</p>
 *
 * @since 2.2.9
 */
final class OidcIdTokenVerifierTest {

    private static final String ISSUER = "https://idp.example.com/oauth2";
    private static final String CLIENT = "pantera-client";
    private static final String KID = "provider-key-1";

    private RSAPrivateKey providerPrivate;
    private RSAPublicKey providerPublic;
    private RSAPrivateKey attackerPrivate;
    private OidcIdTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair provider = gen.generateKeyPair();
        final KeyPair attacker = gen.generateKeyPair();
        this.providerPrivate = (RSAPrivateKey) provider.getPrivate();
        this.providerPublic = (RSAPublicKey) provider.getPublic();
        this.attackerPrivate = (RSAPrivateKey) attacker.getPrivate();
        final JwkSource keys = kid -> KID.equals(kid)
            ? Optional.of(this.providerPublic) : Optional.empty();
        this.verifier = new OidcIdTokenVerifier(keys, ISSUER, CLIENT);
    }

    @Test
    void genuineTokenIsAcceptedWithClaims() {
        final DecodedJWT jwt = this.verifier.verify(this.sign(this.valid(), this.providerPrivate, KID), "n0nce");
        MatcherAssert.assertThat(
            "a token signed by the provider for this client with the right nonce must verify",
            jwt.getClaim("preferred_username").asString(), new IsEqual<>("alice")
        );
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() {
        final String forged = this.sign(this.valid(), this.attackerPrivate, KID);
        MatcherAssert.assertThat(
            "an id_token signed by a key that is NOT the provider's must be rejected",
            this.rejected(forged, "n0nce"), new IsEqual<>(true)
        );
    }

    @Test
    void unsignedTokenIsRejected() {
        final String unsigned = JWT.create()
            .withIssuer(ISSUER).withAudience(CLIENT).withSubject("alice")
            .withClaim("nonce", "n0nce").withExpiresAt(Instant.now().plusSeconds(300))
            .sign(Algorithm.none());
        MatcherAssert.assertThat(
            "an unsigned (alg=none) id_token must be rejected",
            this.rejected(unsigned, "n0nce"), new IsEqual<>(true)
        );
    }

    @Test
    void wrongIssuerIsRejected() {
        final String token = this.sign(
            this.valid().withIssuer("https://evil.example.com"), this.providerPrivate, KID
        );
        MatcherAssert.assertThat(
            "an id_token from a different issuer must be rejected",
            this.rejected(token, "n0nce"), new IsEqual<>(true)
        );
    }

    @Test
    void wrongAudienceIsRejected() {
        final String token = this.sign(
            this.valid().withAudience("some-other-client"), this.providerPrivate, KID
        );
        MatcherAssert.assertThat(
            "an id_token minted for another client must be rejected",
            this.rejected(token, "n0nce"), new IsEqual<>(true)
        );
    }

    @Test
    void expiredTokenIsRejected() {
        final String token = this.sign(
            this.valid().withExpiresAt(Instant.now().minusSeconds(600)), this.providerPrivate, KID
        );
        MatcherAssert.assertThat(
            "an expired id_token must be rejected",
            this.rejected(token, "n0nce"), new IsEqual<>(true)
        );
    }

    @Test
    void nonceMismatchIsRejected() {
        final String token = this.sign(this.valid(), this.providerPrivate, KID);
        MatcherAssert.assertThat(
            "an id_token whose nonce does not match this login's nonce must be rejected (replay/injection)",
            this.rejected(token, "different-nonce"), new IsEqual<>(true)
        );
    }

    @Test
    void unknownKeyIdFailsClosed() {
        final String token = this.sign(this.valid(), this.providerPrivate, "unknown-kid");
        MatcherAssert.assertThat(
            "a kid the JWKS does not know must fail closed, never skip verification",
            this.rejected(token, "n0nce"), new IsEqual<>(true)
        );
    }

    private JWTCreator.Builder valid() {
        return JWT.create()
            .withIssuer(ISSUER)
            .withAudience(CLIENT)
            .withSubject("sub-123")
            .withClaim("preferred_username", "alice")
            .withClaim("nonce", "n0nce")
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(300));
    }

    private String sign(final JWTCreator.Builder builder, final RSAPrivateKey key, final String kid) {
        return builder.withKeyId(kid).sign(Algorithm.RSA256(null, key));
    }

    private boolean rejected(final String token, final String nonce) {
        try {
            this.verifier.verify(token, nonce);
            return false;
        } catch (final OidcVerificationException ex) {
            return true;
        }
    }
}
