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

import io.vertx.core.json.JsonObject;
import java.lang.reflect.Field;
import java.util.Set;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Guards the security-policy admin endpoints' key whitelists and their
 * round-trip validation (reflection/plain-object only: the handlers are
 * otherwise exercised through the TestContainers-backed API tests, and
 * this repo's doctrine forbids new Docker-backed {@code *Test.java}).
 *
 * @since 2.2.9
 */
final class SecurityPolicySettingsHandlerTest {

    @Test
    void requestLimitsWhitelistCoversBothKeys() {
        final Set<String> keys = SecurityPolicySettingsHandlerTest.keys("REQUEST_LIMITS_KEYS");
        MatcherAssert.assertThat("body cap", keys.contains("max_request_body_bytes"), new IsEqual<>(true));
        MatcherAssert.assertThat("fs roots", keys.contains("fs_storage_roots"), new IsEqual<>(true));
        MatcherAssert.assertThat("nothing else", keys.size(), new IsEqual<>(2));
    }

    @Test
    void egressWhitelistCoversTheThreeKeys() {
        final Set<String> keys = SecurityPolicySettingsHandlerTest.keys("EGRESS_KEYS");
        MatcherAssert.assertThat(
            "exact key set",
            keys,
            new IsEqual<>(Set.of(
                "egress_block_private", "egress_allow_hosts", "upstream_credential_allow_hosts"
            ))
        );
    }

    @Test
    void loginThrottleWhitelistCoversBothKeys() {
        final Set<String> keys = SecurityPolicySettingsHandlerTest.keys("LOGIN_THROTTLE_KEYS");
        MatcherAssert.assertThat(
            "exact key set",
            keys,
            new IsEqual<>(Set.of("login_throttle_max_failures", "login_throttle_window_seconds"))
        );
    }

    @Test
    void partialUpdatesAreValidatedAgainstTheMergedConfig() {
        final SecurityPolicySettingsHandler.Section limits =
            SecurityPolicySettingsHandler.requestLimits();
        limits.validate(new JsonObject().put("max_request_body_bytes", "2097152"));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            limits.validate(new JsonObject().put("max_request_body_bytes", "12")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            limits.validate(new JsonObject().put("max_request_body_bytes", "lots")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            limits.validate(new JsonObject().put("fs_storage_roots", "relative/dir")));
        final SecurityPolicySettingsHandler.Section egress = SecurityPolicySettingsHandler.egress();
        egress.validate(new JsonObject().put("egress_block_private", true)
            .put("egress_allow_hosts", "mirror.example, other.example"));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            egress.validate(new JsonObject().put("egress_block_private", "maybe")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            egress.validate(new JsonObject().put("upstream_credential_allow_hosts", "bad host")));
        final SecurityPolicySettingsHandler.Section throttle =
            SecurityPolicySettingsHandler.loginThrottle();
        throttle.validate(new JsonObject().put("login_throttle_max_failures", "3"));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            throttle.validate(new JsonObject().put("login_throttle_window_seconds", "0")));
    }

    @Test
    void currentValuesAreReportedAsStringsForEveryKey() {
        final JsonObject current = SecurityPolicySettingsHandler.egress().current();
        MatcherAssert.assertThat(
            "booleans are reported as strings, like every other settings endpoint",
            current.getValue("egress_block_private") instanceof String, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "every whitelisted key is present so the UI form populates",
            current.fieldNames(), new IsEqual<>(SecurityPolicySettingsHandlerTest.keys("EGRESS_KEYS"))
        );
    }

    @SuppressWarnings("unchecked")
    private static Set<String> keys(final String field) {
        try {
            final Field ref = SecurityPolicySettingsHandler.class.getDeclaredField(field);
            ref.setAccessible(true);
            return (Set<String>) ref.get(null);
        } catch (final ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
