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
package com.auto1.pantera.maven.security;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Iterator;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

/**
 * Unit tests for {@link PgpVerifier}. Generates a fresh RSA key pair
 * once, signs a test payload, and exercises the verified / tampered /
 * untrusted / missing / malformed branches against an in-memory keyring.
 *
 * <p>No filesystem / network / DB dependencies — runs in &lt; 1 s.
 *
 * @since 2.2.0
 */
final class PgpVerifierTest {

    /** Test payload — represents a Maven primary (.jar / .pom). */
    private static final byte[] PAYLOAD =
        "T-S03 maven primary bytes — verify me".getBytes(StandardCharsets.UTF_8);

    /** Generated once, reused across cases. */
    private static PGPSecretKey signingKey;
    private static PGPPublicKey publicKey;
    private static byte[] validSignature;
    private static byte[] otherTrustedSignature;

    /** Different (untrusted) key + signature, for the UNTRUSTED_KEY case. */
    private static PGPSecretKey unknownSigningKey;
    private static byte[] unknownSignature;

    @BeforeAll
    static void buildKeysAndSignatures() throws Exception {
        // RSA 2048 — short enough to be fast in tests, long enough that
        // BC will accept it for signing.
        final PGPKeyRingGenerator trustedRing = newRingGenerator("trusted@example.com");
        final PGPSecretKey trusted = trustedRing.generateSecretKeyRing().getSecretKey();
        signingKey = trusted;
        publicKey = trusted.getPublicKey();
        validSignature = signDetached(PAYLOAD, trusted);
        otherTrustedSignature = signDetached("different".getBytes(), trusted);

        final PGPKeyRingGenerator unknownRing = newRingGenerator("unknown@example.com");
        unknownSigningKey = unknownRing.generateSecretKeyRing().getSecretKey();
        unknownSignature = signDetached(PAYLOAD, unknownSigningKey);
    }

    @Test
    @DisplayName("VERIFIED: trusted key, untampered payload, fresh signature")
    void verified() {
        final InMemoryKeyringStore store = new InMemoryKeyringStore();
        store.findByKeyId(publicKey.getKeyID()); // no-op
        final PgpVerifier verifier = new PgpVerifier(
            id -> id == publicKey.getKeyID()
                ? java.util.Optional.of(publicKey)
                : java.util.Optional.empty()
        );

        final PgpVerifier.Result result = verifier.verify(PAYLOAD, validSignature);

        MatcherAssert.assertThat(result, new IsEqual<>(PgpVerifier.Result.VERIFIED));
    }

    @Test
    @DisplayName("TAMPERED: trusted key but signature was made over different payload")
    void tampered() {
        final PgpVerifier verifier = new PgpVerifier(
            id -> id == publicKey.getKeyID()
                ? java.util.Optional.of(publicKey)
                : java.util.Optional.empty()
        );

        // Signature was made for "different" bytes; verifying against the
        // payload bytes must fail.
        final PgpVerifier.Result result =
            verifier.verify(PAYLOAD, otherTrustedSignature);

        MatcherAssert.assertThat(result, new IsEqual<>(PgpVerifier.Result.TAMPERED));
    }

    @Test
    @DisplayName("UNTRUSTED_KEY: signature is valid but signing key not in keyring")
    void untrustedKey() {
        final PgpVerifier verifier = new PgpVerifier(
            // Empty keyring — no key returned for any id.
            id -> java.util.Optional.empty()
        );

        final PgpVerifier.Result result =
            verifier.verify(PAYLOAD, unknownSignature);

        MatcherAssert.assertThat(
            result, new IsEqual<>(PgpVerifier.Result.UNTRUSTED_KEY)
        );
    }

    @Test
    @DisplayName("MISSING_SIGNATURE: null / empty .asc")
    void missingSignature() {
        final PgpVerifier verifier = new PgpVerifier(
            id -> java.util.Optional.empty()
        );
        MatcherAssert.assertThat(
            "reason: null .asc → MISSING_SIGNATURE",
            verifier.verify(PAYLOAD, null),
            new IsEqual<>(PgpVerifier.Result.MISSING_SIGNATURE)
        );
        MatcherAssert.assertThat(
            "reason: empty .asc → MISSING_SIGNATURE",
            verifier.verify(PAYLOAD, new byte[0]),
            new IsEqual<>(PgpVerifier.Result.MISSING_SIGNATURE)
        );
    }

    @Test
    @DisplayName("MALFORMED: .asc bytes are not a valid OpenPGP packet stream")
    void malformed() {
        final PgpVerifier verifier = new PgpVerifier(
            id -> java.util.Optional.empty()
        );
        final byte[] garbage = "not a pgp signature".getBytes(StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            verifier.verify(PAYLOAD, garbage),
            new IsEqual<>(PgpVerifier.Result.MALFORMED)
        );
    }

    @Test
    @DisplayName("MALFORMED: null / empty payload rejected")
    void malformedPayload() {
        final PgpVerifier verifier = new PgpVerifier(
            id -> java.util.Optional.empty()
        );
        MatcherAssert.assertThat(
            "reason: null payload → MALFORMED",
            verifier.verify(null, validSignature),
            new IsEqual<>(PgpVerifier.Result.MALFORMED)
        );
        MatcherAssert.assertThat(
            "reason: empty payload → MALFORMED",
            verifier.verify(new byte[0], validSignature),
            new IsEqual<>(PgpVerifier.Result.MALFORMED)
        );
    }

    @Test
    @DisplayName("InMemoryKeyringStore round-trip: addAsciiArmored → findByKeyId")
    void inMemoryKeyringRoundTrip() throws Exception {
        final InMemoryKeyringStore store = new InMemoryKeyringStore();
        store.addAsciiArmored(armoredPublicKey(publicKey));
        MatcherAssert.assertThat(
            "reason: round-tripped key resolves",
            store.findByKeyId(publicKey.getKeyID()).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "reason: unknown key id resolves empty",
            store.findByKeyId(0xDEADBEEFL).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "reason: at least one key indexed",
            store.size() >= 1, new IsEqual<>(true)
        );
    }

    @Test
    @DisplayName("End-to-end: signature verifies against keyring populated from armored block")
    void endToEnd() throws Exception {
        final InMemoryKeyringStore store = new InMemoryKeyringStore();
        store.addAsciiArmored(armoredPublicKey(publicKey));
        final PgpVerifier verifier = new PgpVerifier(store);

        MatcherAssert.assertThat(
            verifier.verify(PAYLOAD, validSignature),
            new IsEqual<>(PgpVerifier.Result.VERIFIED)
        );
    }

    // ===== fixture helpers =====

    /**
     * Build a fresh RSA-2048 keyring generator with a single user id.
     */
    private static PGPKeyRingGenerator newRingGenerator(final String userId) throws Exception {
        final RSAKeyPairGenerator rsa = new RSAKeyPairGenerator();
        rsa.init(new RSAKeyGenerationParameters(
            BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 12
        ));
        final PGPKeyPair pair = new BcPGPKeyPair(
            PublicKeyAlgorithmTags.RSA_GENERAL,
            rsa.generateKeyPair(),
            new Date()
        );
        final PGPSignatureSubpacketGenerator subs = new PGPSignatureSubpacketGenerator();
        subs.setKeyFlags(false, KeyFlags.SIGN_DATA | KeyFlags.CERTIFY_OTHER);
        return new PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pair, userId,
            new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
            subs.generate(), null,
            new BcPGPContentSignerBuilder(
                pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256
            ),
            new BcPBESecretKeyEncryptorBuilder(
                org.bouncycastle.openpgp.PGPEncryptedData.AES_256,
                new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256)
            ).build(new char[0])
        );
    }

    /**
     * Produce an ASCII-armored detached signature for {@code payload}
     * using {@code secretKey}.
     */
    private static byte[] signDetached(
        final byte[] payload, final PGPSecretKey secretKey
    ) throws PGPException, java.io.IOException {
        final PGPPrivateKey privateKey = secretKey.extractPrivateKey(
            new org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder(
                new BcPGPDigestCalculatorProvider()
            ).build(new char[0])
        );
        final PGPSignatureGenerator gen = new PGPSignatureGenerator(
            new BcPGPContentSignerBuilder(
                secretKey.getPublicKey().getAlgorithm(),
                HashAlgorithmTags.SHA256
            )
        );
        gen.init(PGPSignature.BINARY_DOCUMENT, privateKey);
        gen.update(payload);
        final PGPSignature signature = gen.generate();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(bytes);
             BCPGOutputStream packetOut = new BCPGOutputStream(armored)) {
            signature.encode(packetOut);
        }
        return bytes.toByteArray();
    }

    /**
     * Produce the ASCII-armored public-key block bytes for a single key.
     */
    private static byte[] armoredPublicKey(final PGPPublicKey key) throws java.io.IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(bytes)) {
            final PGPPublicKeyRing ring = new PGPPublicKeyRing(
                java.util.Collections.singletonList(key)
            );
            ring.encode(armored);
        }
        return bytes.toByteArray();
    }
}
