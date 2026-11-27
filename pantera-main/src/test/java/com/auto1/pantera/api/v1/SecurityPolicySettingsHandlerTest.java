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
    void failedLoginsWhitelistCoversTheTenKeys() {
        MatcherAssert.assertThat(
            SecurityPolicySettingsHandlerTest.keys("FAILED_LOGINS_KEYS"),
            new IsEqual<>(
                Set.of(
                    "failed_logins_delay_from_failure", "failed_logins_first_delay_seconds",
                    "failed_logins_max_delay_seconds", "failed_logins_lock_after",
                    "failed_logins_unlock_after_minutes", "failed_logins_forget_after_minutes",
                    "failed_logins_count_rejected_keys", "failed_logins_lock_all_credentials",
                    "failed_logins_second_factor_limit",
                    "failed_logins_second_factor_pause_minutes"
                )
            )
        );
    }

    @Test
    void failedLoginsUpdatesAreValidatedAgainstTheMergedPolicy() {
        final SecurityPolicySettingsHandler.Section section =
            SecurityPolicySettingsHandler.failedLogins();
        section.validate(
            new JsonObject().put("failed_logins_lock_after", "10")
                .put("failed_logins_unlock_after_minutes", 30)
                .put("failed_logins_count_rejected_keys", true)
                .put("failed_logins_lock_all_credentials", "false")
        );
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("failed_logins_lock_after", "101")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("failed_logins_lock_after", "three")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("failed_logins_unlock_after_minutes", "-1")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("failed_logins_count_rejected_keys", "maybe")));
        Assertions.assertThrows(
            IllegalArgumentException.class,
            (Executable) () -> section.validate(
                new JsonObject().put("failed_logins_first_delay_seconds", "30")
                    .put("failed_logins_max_delay_seconds", "10")
            ),
            "a longest delay below the first delay is refused as a pair"
        );
    }

    @Test
    void failedLoginsCurrentValuesAreStringsForEveryKey() {
        final JsonObject current = SecurityPolicySettingsHandler.failedLogins().current();
        MatcherAssert.assertThat(
            "every key is reported",
            current.fieldNames(),
            new IsEqual<>(SecurityPolicySettingsHandlerTest.keys("FAILED_LOGINS_KEYS"))
        );
        MatcherAssert.assertThat(
            "as a string, like the other sections",
            current.getValue("failed_logins_lock_after"), new IsEqual<>("3")
        );
    }

    @Test
    void passwordSignInHasAModeForEveryProviderThatTakesPasswords() {
        final JsonObject current = SecurityPolicySettingsHandler.passwordSignIn().current();
        MatcherAssert.assertThat(
            "one key per provider type",
            current.fieldNames(),
            new IsEqual<>(
                Set.of(
                    "password_sign_in_okta", "password_sign_in_keycloak",
                    "password_sign_in_github"
                )
            )
        );
        MatcherAssert.assertThat(
            "the whitelist is the same set",
            SecurityPolicySettingsHandlerTest.keys("PASSWORD_SIGN_IN_KEYS"),
            new IsEqual<>(current.fieldNames())
        );
        MatcherAssert.assertThat(
            "passwords are taken everywhere unless an administrator says otherwise",
            current.getValue("password_sign_in_okta"), new IsEqual<>("all")
        );
    }

    @Test
    void passwordSignInAcceptsItsThreeModesOnly() {
        final SecurityPolicySettingsHandler.Section section =
            SecurityPolicySettingsHandler.passwordSignIn();
        section.validate(
            new JsonObject().put("password_sign_in_okta", "form")
                .put("password_sign_in_keycloak", "off")
                .put("password_sign_in_github", "all")
        );
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("password_sign_in_okta", "ui")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("password_sign_in_okta", "")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("password_sign_in_okta", true)));
    }

    @Test
    void signInReportIsOnAndKeepsNinetyDaysUnlessSetOtherwise() {
        final JsonObject current = SecurityPolicySettingsHandler.signInReport().current();
        MatcherAssert.assertThat(
            current,
            new IsEqual<>(
                new JsonObject().put("sign_in_report_enabled", "true")
                    .put("sign_in_report_retention_days", "90")
            )
        );
        MatcherAssert.assertThat(
            "the whitelist is the same set",
            SecurityPolicySettingsHandlerTest.keys("SIGN_IN_REPORT_KEYS"),
            new IsEqual<>(current.fieldNames())
        );
    }

    @Test
    void signInReportRefusesARetentionOutsideItsRange() {
        final SecurityPolicySettingsHandler.Section section =
            SecurityPolicySettingsHandler.signInReport();
        section.validate(
            new JsonObject().put("sign_in_report_enabled", false)
                .put("sign_in_report_retention_days", "365")
        );
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("sign_in_report_retention_days", "0")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("sign_in_report_retention_days", "3651")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            section.validate(new JsonObject().put("sign_in_report_enabled", "yes")));
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
        final SecurityPolicySettingsHandler.Section failed =
            SecurityPolicySettingsHandler.failedLogins();
        failed.validate(new JsonObject().put("failed_logins_second_factor_limit", "0"));
        failed.validate(new JsonObject().put("failed_logins_second_factor_pause_minutes", "1440"));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            failed.validate(new JsonObject().put("failed_logins_second_factor_limit", "101")));
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            failed.validate(new JsonObject().put("failed_logins_second_factor_pause_minutes", "0")));
    }

    @Test
    void nullValueIsRefusedByValidation() {
        // B100: a JSON null passed validation as "" and then failed the
        // write with an NPE (500), possibly after other keys were stored.
        Assertions.assertThrows(IllegalArgumentException.class, (Executable) () ->
            SecurityPolicySettingsHandler.egress().validate(
                new JsonObject().put("egress_allow_hosts", "a.example")
                    .putNull("egress_block_private")
            )
        );
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
