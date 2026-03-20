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
package com.auto1.pantera.http.client.auth;

/**
 * Format of Access Token used for Bearer authentication.
 * See <a href="https://tools.ietf.org/html/rfc6750#section-1.3">Overview</a>
 *
 * @since 0.5
 */
public interface TokenFormat {

    /**
     * Reads token string from bytes.
     *
     * @param bytes Bytes.
     * @return Token string.
     */
    String token(byte[] bytes);
}
