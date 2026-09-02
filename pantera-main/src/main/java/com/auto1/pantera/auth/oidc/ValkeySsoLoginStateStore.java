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

import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.log.EcsLogger;
import io.lettuce.core.SetArgs;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cluster-shared {@link SsoLoginStateStore}: {@code SET state nonce NX PX
 * ttl} on issue, {@code GETDEL} on consume, so a login started on one node
 * completes on any other exactly once. Lookup failures fail closed (the
 * callback is refused), never open.
 *
 * @since 2.2.9
 */
public final class ValkeySsoLoginStateStore implements SsoLoginStateStore {

    /**
     * Key prefix.
     */
    private static final String PREFIX = "pantera:sso-state:";

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.auth.oidc";

    /**
     * Valkey connection.
     */
    private final ValkeyConnection valkey;

    /**
     * Pending-login lifetime.
     */
    private final Duration ttl;

    /**
     * Random source.
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * Ctor.
     * @param valkey Valkey connection
     * @param ttl Pending-login lifetime
     */
    public ValkeySsoLoginStateStore(final ValkeyConnection valkey, final Duration ttl) {
        this.valkey = valkey;
        this.ttl = ttl;
    }

    @Override
    public String newState() {
        return this.token();
    }

    @Override
    public CompletionStage<String> issue(final String state) {
        final String nonce = this.token();
        return this.valkey.async()
            .set(
                PREFIX + state, nonce.getBytes(StandardCharsets.UTF_8),
                SetArgs.Builder.nx().px(this.ttl.toMillis())
            )
            .thenApply(reply -> {
                if (!"OK".equals(reply)) {
                    throw new IllegalStateException("SSO state already pending");
                }
                return nonce;
            });
    }

    @Override
    public CompletionStage<Optional<String>> consume(final String state) {
        if (state == null || state.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return this.valkey.async().getdel(PREFIX + state)
            .thenApply(bytes -> bytes == null
                ? Optional.<String>empty()
                : Optional.of(new String(bytes, StandardCharsets.UTF_8)))
            .exceptionally(err -> {
                EcsLogger.warn(LOGGER)
                    .message("SSO login state lookup failed; refusing callback")
                    .eventCategory("authentication")
                    .eventAction("sso_callback")
                    .eventOutcome("failure")
                    .field("log.source", "application")
                    .error(err)
                    .log();
                return Optional.empty();
            });
    }

    private String token() {
        final byte[] raw = new byte[24];
        this.random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
