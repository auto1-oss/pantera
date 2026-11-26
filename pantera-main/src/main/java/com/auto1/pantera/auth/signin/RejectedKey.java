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

/**
 * What is known of an API key that was rejected.
 *
 * @param reason Why it was rejected: {@code api_key_expired},
 *  {@code api_key_revoked}, {@code api_key_refused} (valid, but its owner
 *  is disabled or it was sent with another user name) or
 *  {@code api_key_unknown} (not signed by this installation)
 * @param owner The owner the key proves; for an unknown key the user
 *  name it was sent with or claims, which proves nothing
 * @param tokenId Id of the key, empty for an unknown key
 * @param verified Whether the signature proves the owner
 * @since 2.2.15
 */
public record RejectedKey(String reason, String owner, String tokenId, boolean verified) {

    /**
     * Reason: the key is past its expiry date.
     */
    public static final String EXPIRED = "api_key_expired";

    /**
     * Reason: the key was revoked or removed.
     */
    public static final String REVOKED = "api_key_revoked";

    /**
     * Reason: the key is valid but was not accepted.
     */
    public static final String REFUSED = "api_key_refused";

    /**
     * Reason: this installation did not sign the key.
     */
    public static final String UNKNOWN = "api_key_unknown";

    /**
     * What the client is told.
     *
     * @return Text of the answer
     */
    public String message() {
        final String text;
        if (RejectedKey.EXPIRED.equals(this.reason)) {
            text = "API key expired";
        } else if (RejectedKey.REVOKED.equals(this.reason)) {
            text = "API key revoked";
        } else if (RejectedKey.REFUSED.equals(this.reason)) {
            text = "API key not accepted";
        } else {
            text = "API key not valid here";
        }
        return text;
    }
}
