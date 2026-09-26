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

import java.security.interfaces.RSAPublicKey;
import java.util.Optional;

/**
 * Source of an OIDC provider's RSA signing keys, addressed by JWK
 * {@code kid}. Production uses {@link HttpJwkSource} (the provider's JWKS
 * endpoint, cached); tests supply a stub keyed to a locally generated
 * pair.
 *
 * @since 2.2.9
 */
@FunctionalInterface
public interface JwkSource {

    /**
     * Resolve the public key for a key id.
     *
     * @param kid JWK key id from the token header
     * @return Public key, or empty when unknown (the verifier fails closed)
     */
    Optional<RSAPublicKey> key(String kid);
}
