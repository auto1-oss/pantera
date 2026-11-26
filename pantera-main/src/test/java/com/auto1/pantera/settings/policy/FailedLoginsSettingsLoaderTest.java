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
package com.auto1.pantera.settings.policy;

import java.util.Map;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link FailedLoginsSettingsLoader}: database row, then environment, then
 * the defaults; a value that cannot be used falls back alone.
 */
final class FailedLoginsSettingsLoaderTest {

    @AfterEach
    void tearDown() {
        FailedLoginsSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        MatcherAssert.assertThat(
            FailedLoginsSettingsLoader.activeSupplier().get(),
            new IsEqual<>(FailedLoginsConfig.defaults())
        );
    }

    @Test
    void databaseRowsWinOverEnvironment() {
        final FailedLoginsSettingsLoader loader = new FailedLoginsSettingsLoader(
            FakeAuthSettings.withRows(
                Map.of(
                    "failed_logins_delay_from_failure", "4",
                    "failed_logins_first_delay_seconds", "2",
                    "failed_logins_max_delay_seconds", "120",
                    "failed_logins_lock_after", "10",
                    "failed_logins_unlock_after_minutes", "30",
                    "failed_logins_forget_after_minutes", "15",
                    "failed_logins_count_rejected_keys", "true"
                )
            ),
            Map.of("PANTERA_FAILED_LOGINS_LOCK_AFTER", "99")::get
        );
        MatcherAssert.assertThat(
            loader.get(), new IsEqual<>(new FailedLoginsConfig(4, 2, 120, 10, 30, 15, true))
        );
    }

    @Test
    void environmentWinsOverDefaultsWhenThereIsNoRow() {
        final FailedLoginsSettingsLoader loader = new FailedLoginsSettingsLoader(
            FakeAuthSettings.empty(),
            Map.of(
                "PANTERA_FAILED_LOGINS_LOCK_AFTER", "7",
                "PANTERA_FAILED_LOGINS_UNLOCK_AFTER_MINUTES", "45",
                "PANTERA_FAILED_LOGINS_COUNT_REJECTED_KEYS", "TRUE"
            )::get
        );
        MatcherAssert.assertThat(
            loader.get(), new IsEqual<>(new FailedLoginsConfig(2, 1, 60, 7, 45, 0, true))
        );
    }

    @ParameterizedTest
    @CsvSource({
        "failed_logins_delay_from_failure, 101",
        "failed_logins_delay_from_failure, -1",
        "failed_logins_first_delay_seconds, 0",
        "failed_logins_max_delay_seconds, 3601",
        "failed_logins_lock_after, 101",
        "failed_logins_lock_after, abc",
        "failed_logins_unlock_after_minutes, 1441",
        "failed_logins_forget_after_minutes, -5"
    })
    void valueOutsideItsRangeFallsBackAloneToItsDefault(final String key, final String value) {
        final FailedLoginsSettingsLoader loader = new FailedLoginsSettingsLoader(
            FakeAuthSettings.withRows(Map.of(key, value)), Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            loader.get(), new IsEqual<>(FailedLoginsConfig.defaults())
        );
    }

    @Test
    void invalidValueKeepsItsSiblings() {
        final FailedLoginsSettingsLoader loader = new FailedLoginsSettingsLoader(
            FakeAuthSettings.withRows(
                Map.of(
                    "failed_logins_lock_after", "500",
                    "failed_logins_unlock_after_minutes", "30"
                )
            ),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            "the lock limit falls back to its default",
            loader.get().lockAfterFailures(), new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "the unlock time is kept",
            loader.get().unlockAfterMinutes(), new IsEqual<>(30)
        );
    }

    @Test
    void longestDelayBelowTheFirstDelayIsRaisedToIt() {
        final FailedLoginsSettingsLoader loader = new FailedLoginsSettingsLoader(
            FakeAuthSettings.withRows(
                Map.of(
                    "failed_logins_first_delay_seconds", "30",
                    "failed_logins_max_delay_seconds", "10"
                )
            ),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            "the first delay is kept",
            loader.get().firstDelaySeconds(), new IsEqual<>(30)
        );
        MatcherAssert.assertThat(
            "the longest delay cannot be shorter: it takes its default",
            loader.get().maxDelaySeconds(), new IsEqual<>(60)
        );
    }

    @Test
    void wordThatIsNotTrueSwitchesKeyCountingOff() {
        final FailedLoginsSettingsLoader loader = new FailedLoginsSettingsLoader(
            FakeAuthSettings.withRows(Map.of("failed_logins_count_rejected_keys", "yes")),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            loader.get().countRejectedKeys(), new IsEqual<>(false)
        );
    }

    @Test
    void lockScopeIsReadFromItsRow() {
        final FailedLoginsSettingsLoader loader = new FailedLoginsSettingsLoader(
            FakeAuthSettings.withRows(Map.of("failed_logins_lock_all_credentials", "false")),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            loader.get().lockAllCredentials(), new IsEqual<>(false)
        );
    }

    @Test
    void installedLoaderIsTheActiveSupplier() {
        FailedLoginsSettingsLoader.install(
            FakeAuthSettings.withRows(Map.of("failed_logins_lock_after", "9")),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            FailedLoginsSettingsLoader.activeSupplier().get().lockAfterFailures(),
            new IsEqual<>(9)
        );
    }
}
