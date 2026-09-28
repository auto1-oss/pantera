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
package com.auto1.pantera.api.v1.download;

import com.auto1.pantera.http.log.EcsLogger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Resolution of the HMAC key that signs direct-download tokens.
 *
 * <p>SECURITY (2.2.9, SecOps download-token-hmac): before 2.2.9 the key
 * fell back to {@code pantera-download-<pid>-<user.name>} — zero entropy,
 * and fixed at {@code pantera-download-1-pantera} in the shipped container
 * — so anyone could mint a token for any repository path and read it
 * through the JWT-exempt {@code download-direct} route. The docs even
 * claimed a random default was generated. Resolution order is now:</p>
 *
 * <ol>
 *   <li>{@code PANTERA_DOWNLOAD_TOKEN_SECRET} — an operator secret of at
 *     least 32 bytes; anything shorter aborts startup (fail closed) rather
 *     than weakening the key.</li>
 *   <li>A SecureRandom 256-bit key persisted once in the shared
 *     {@code auth_settings} table so every HA node signs and verifies
 *     with the same key (insert-if-absent, then read back, so racing
 *     nodes converge on one winner).</li>
 *   <li>With neither (database-less single-instance mode): an ephemeral
 *     SecureRandom key for this process — unforgeable, just not shared.</li>
 * </ol>
 *
 * <p>Process metadata is never used. The chosen source is logged once
 * (never the key material).</p>
 *
 * @since 2.2.9
 */
public final class DownloadTokenKey {

    /**
     * Operator secret environment variable.
     */
    public static final String ENV = "PANTERA_DOWNLOAD_TOKEN_SECRET";

    /**
     * Minimum operator-secret length in bytes.
     */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * {@code auth_settings} key holding the persisted key (base64).
     */
    static final String SETTING = "download_token_secret";

    /**
     * Generated key size in bytes (256 bits).
     */
    private static final int KEY_BYTES = 32;

    private DownloadTokenKey() {
    }

    /**
     * Resolve the signing key.
     *
     * @param env Environment (normally {@code System.getenv()})
     * @param store Shared settings store when the deployment is DB-backed
     * @return Key bytes
     * @throws IllegalStateException when the operator secret is too short
     */
    public static byte[] resolve(final Map<String, String> env, final Optional<Store> store) {
        final String secret = env.get(ENV);
        if (secret != null) {
            final byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                    ENV + " must be at least " + MIN_SECRET_BYTES
                        + " bytes of random material; refusing to start with a weak key"
                );
            }
            log("environment");
            return bytes;
        }
        if (store.isPresent()) {
            return persisted(store.get());
        }
        log("ephemeral");
        return random();
    }

    /**
     * Read the shared key, generating and persisting one on first boot.
     *
     * @param store Shared settings store
     * @return Key bytes
     */
    private static byte[] persisted(final Store store) {
        final Optional<String> existing = store.get(SETTING);
        if (existing.isEmpty()) {
            store.putIfAbsent(SETTING, Base64.getEncoder().encodeToString(random()));
        }
        final String stored = store.get(SETTING).orElseThrow(
            () -> new IllegalStateException(
                "download token key could not be persisted in auth_settings"
            )
        );
        log(existing.isEmpty() ? "database (generated)" : "database");
        return Base64.getDecoder().decode(stored);
    }

    private static byte[] random() {
        final byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static void log(final String source) {
        EcsLogger.info("com.auto1.pantera.api")
            .message("Download token signing key resolved from " + source)
            .eventCategory("configuration")
            .eventAction("download_token_key_resolve")
            .eventOutcome("success")
            .field("event.reason", source)
            .field("log.source", "application")
            .log();
    }

    /**
     * Minimal shared key/value contract, satisfied by
     * {@code AuthSettingsDao} in production.
     */
    public interface Store {

        /**
         * @param key Setting key
         * @return Stored value, if any
         */
        Optional<String> get(String key);

        /**
         * Insert only if no value exists (must not overwrite).
         *
         * @param key Setting key
         * @param value Value
         */
        void putIfAbsent(String key, String value);
    }
}
