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
package com.auto1.pantera.npm.security;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link NpmPackageSigner}: proves the emitted {@code dist.signatures}
 * entry cryptographically verifies against the registry's own public key
 * served at {@code GET /-/npm/v1/keys} — the exact check {@code npm audit
 * signatures} performs against a real registry.
 */
final class NpmPackageSignerTest {

    @Test
    void signatureVerifiesAgainstTheServedPublicKey() throws Exception {
        final Storage storage = new InMemoryStorage();
        final NpmSigningKeys keys = new NpmSigningKeys(storage);
        final NpmPackageSigner signer = new NpmPackageSigner(keys);
        final Optional<NpmPackageSigner.Signed> signed = signer.sign(
            "simple-npm-project", "1.0.0", "sha512-abc123=="
        ).toCompletableFuture().join();
        MatcherAssert.assertThat("a signature was produced", signed.isPresent(), new IsEqual<>(true));

        final NpmSigningKeys.SigningKeyPair pair = keys.keyPair().toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the signature's keyid matches the served key's keyid",
            signed.get().keyId(),
            new IsEqual<>(pair.keyId())
        );

        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.publicKey());
        verifier.update("simple-npm-project@1.0.0:sha512-abc123==".getBytes(StandardCharsets.UTF_8));
        MatcherAssert.assertThat(
            "the signature verifies against the registry's public key",
            verifier.verify(Base64.getDecoder().decode(signed.get().sig())),
            new IsEqual<>(true)
        );
    }

    @Test
    void tamperedPayloadFailsVerification() throws Exception {
        final Storage storage = new InMemoryStorage();
        final NpmSigningKeys keys = new NpmSigningKeys(storage);
        final NpmPackageSigner signer = new NpmPackageSigner(keys);
        final Optional<NpmPackageSigner.Signed> signed = signer.sign(
            "simple-npm-project", "1.0.0", "sha512-abc123=="
        ).toCompletableFuture().join();
        final NpmSigningKeys.SigningKeyPair pair = keys.keyPair().toCompletableFuture().join();

        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.publicKey());
        // Verify against a DIFFERENT integrity value than what was signed.
        verifier.update("simple-npm-project@1.0.0:sha512-TAMPERED==".getBytes(StandardCharsets.UTF_8));
        MatcherAssert.assertThat(
            "a signature computed over different content must not verify",
            verifier.verify(Base64.getDecoder().decode(signed.get().sig())),
            new IsEqual<>(false)
        );
    }

    @Test
    void isANoOpWhenIntegrityIsAbsent() {
        final Storage storage = new InMemoryStorage();
        final NpmPackageSigner signer = new NpmPackageSigner(storage);
        final Optional<NpmPackageSigner.Signed> signed = signer.sign(
            "legacy-pkg", "0.0.1", null
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "signing is skipped, not a hard failure, when dist.integrity is absent",
            signed.isPresent(),
            new IsEqual<>(false)
        );
    }
}
