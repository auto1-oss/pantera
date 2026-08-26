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
package com.auto1.pantera.api.v1;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Guards the client-base-URL admin endpoint's key whitelist ({@code
 * AdminAuthHandler#CLIENT_BASE_KEYS}): a forgotten whitelist entry for a new
 * field makes {@code PUT /api/v1/admin/client-base-url-settings} silently
 * reject it with {@code "Unknown client-base-url setting"} instead of
 * writing it -- the exact way {@code client_base_url} (fixwave-h, 2.3.0)
 * would fail without this entry.
 *
 * <p>Reflection-only, no Vert.x {@code RoutingContext} / HTTP / DB
 * involved: {@code AdminAuthHandler}'s endpoints are otherwise only
 * exercised through {@code AsyncApiTestBase}, which requires a
 * TestContainers Postgres -- this repo's testing doctrine forbids adding
 * new Docker-backed {@code *Test.java} classes, and {@code pantera-main}'s
 * test classpath carries no Mockito to fake {@code RoutingContext} with.
 * The whitelist itself is a plain static field, so it is fully verifiable
 * without either.</p>
 *
 * @since 2.3.0
 */
final class AdminAuthHandlerClientBaseKeyTest {

    @Test
    @SuppressWarnings("unchecked")
    void clientBaseKeysWhitelistIncludesTheCanonicalBaseUrlSetting()
        throws ReflectiveOperationException {
        final Field field = AdminAuthHandler.class.getDeclaredField("CLIENT_BASE_KEYS");
        field.setAccessible(true);
        final Set<String> keys = (Set<String>) field.get(null);
        MatcherAssert.assertThat(
            "the whitelist must accept the canonical client_base_url key, or "
                + "PUT /api/v1/admin/client-base-url-settings rejects it with "
                + "400 \"Unknown client-base-url setting\" instead of writing it",
            keys.contains("client_base_url"), new IsEqual<>(true)
        );
    }

    @Test
    void clientBaseKeysWhitelistStillIncludesTheTwoPreExistingSettings() {
        final Set<String> keys = AdminAuthHandlerClientBaseKeyTest.clientBaseKeys();
        MatcherAssert.assertThat(
            "trust_forwarded_headers", keys.contains("trust_forwarded_headers"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "client_base_host_allowlist",
            keys.contains("client_base_host_allowlist"), new IsEqual<>(true)
        );
    }

    @SuppressWarnings("unchecked")
    private static Set<String> clientBaseKeys() {
        try {
            final Field field = AdminAuthHandler.class.getDeclaredField("CLIENT_BASE_KEYS");
            field.setAccessible(true);
            return (Set<String>) field.get(null);
        } catch (final ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
