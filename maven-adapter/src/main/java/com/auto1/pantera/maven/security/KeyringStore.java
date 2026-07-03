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

import java.util.Optional;
import org.bouncycastle.openpgp.PGPPublicKey;

/**
 * Trusted-key lookup for {@link PgpVerifier} — the T-S03 keyring
 * abstraction.
 *
 * <p>Implementations resolve a long key ID (the 64-bit identifier in
 * the OpenPGP signature packet, returned by
 * {@link org.bouncycastle.openpgp.PGPSignature#getKeyID()}) to its
 * registered ASCII-armored public key. Returning {@link Optional#empty()}
 * means "no admin has uploaded a key with that ID" — the verifier maps
 * that to {@link PgpVerifier.Result#UNTRUSTED_KEY}.
 *
 * <p>Provided implementations:
 * <ul>
 *   <li>{@link JdbcKeyringStore} — backed by the {@code pgp_keyring}
 *       table populated via the admin REST endpoint (T-S03 follow-up).</li>
 *   <li>{@link InMemoryKeyringStore} — for tests + non-DB boots.</li>
 * </ul>
 *
 * @since 2.2.0
 */
public interface KeyringStore {

    /**
     * Find a trusted public key by its 64-bit long key ID.
     *
     * @param keyId Long key ID from the signature packet
     * @return Matching public key, or empty when not registered
     */
    Optional<PGPPublicKey> findByKeyId(long keyId);
}
