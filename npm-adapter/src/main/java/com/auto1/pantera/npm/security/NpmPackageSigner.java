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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Signs a published package version the way the public npm registry does:
 * an ECDSA-SHA256 signature over {@code "<name>@<version>:<integrity>"},
 * verifiable by any client (e.g. {@code npm audit signatures}) against the
 * public key served at {@code GET /-/npm/v1/keys}.
 *
 * @since 2.3.0
 */
public final class NpmPackageSigner {

    /**
     * Signature algorithm matching npm's own {@code ecdsa-sha2-nistp256} scheme.
     */
    private static final String ALGORITHM = "SHA256withECDSA";

    /**
     * Registry signing keypair.
     */
    private final NpmSigningKeys keys;

    /**
     * Ctor.
     *
     * @param storage Storage backing this repository
     */
    public NpmPackageSigner(final Storage storage) {
        this(new NpmSigningKeys(storage));
    }

    /**
     * Ctor with an explicit keypair source (tests).
     *
     * @param keys Registry signing keypair source
     */
    NpmPackageSigner(final NpmSigningKeys keys) {
        this.keys = keys;
    }

    /**
     * Sign a published version. A no-op (empty result) when {@code integrity}
     * is absent — some legacy publish payloads carry only {@code shasum},
     * and signing must never block or corrupt an otherwise-valid publish.
     *
     * @param name Package name
     * @param version Version being published
     * @param integrity {@code dist.integrity} value (e.g. {@code sha512-...}),
     *  or {@code null}/empty when absent
     * @return Completion stage with the signature, or empty when unsignable
     */
    public CompletionStage<Optional<Signed>> sign(
        final String name, final String version, final String integrity
    ) {
        if (integrity == null || integrity.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        }
        return this.keys.keyPair().thenApply(pair -> {
            try {
                final String payload = String.format("%s@%s:%s", name, version, integrity);
                final Signature signer = Signature.getInstance(ALGORITHM);
                signer.initSign(pair.privateKey());
                signer.update(payload.getBytes(StandardCharsets.UTF_8));
                final String sig = Base64.getEncoder().encodeToString(signer.sign());
                return Optional.of(new Signed(pair.keyId(), sig));
            } catch (final GeneralSecurityException ex) {
                throw new IllegalStateException("Failed to sign npm package version", ex);
            }
        });
    }

    /**
     * A computed {@code dist.signatures[]} entry.
     *
     * @param keyId {@code SHA256:<base64>} keyid of the signing key
     * @param sig Base64 ECDSA signature
     */
    public record Signed(String keyId, String sig) {
    }
}
