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

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.auth.RecordingAuthFactory;
import com.auto1.pantera.auth.failedlogins.FailedLogins;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.settings.YamlSettings;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sign-in chain that {@link YamlSettings} builds counts failed logins
 * and locks a user: with the lock in force no credential of that user
 * reaches a provider, a login the cache remembers included.
 *
 * <p>The password provider is {@link RecordingAuthFactory}, which stands
 * for one that asks an external identity provider.</p>
 */
final class FailedLoginsChainTest {

    /**
     * A credential with the form of an API key that no provider accepts.
     */
    private static final String STALE_KEY =
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c3RhbGUtc2lnbmF0dXJl";

    /**
     * Settings directory.
     */
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        RecordingAuthFactory.SEEN.clear();
        FailedLoginsChainTest.policy(Map.of());
    }

    @AfterEach
    void tearDown() {
        FailedLoginsSettingsLoader.uninstall();
    }

    @Test
    void lockedUserReachesNoProvider() throws Exception {
        final Authentication chain = this.settings().authz().authentication();
        for (int idx = 0; idx < 3; idx += 1) {
            new PasswordCheck(chain).now("alice", "guess-" + idx);
        }
        final PasswordCheck.Outcome outcome =
            new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD);
        MatcherAssert.assertThat(
            "the right password is refused once the user is locked",
            outcome.user().isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "as locked",
            outcome.refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("account_locked"))
        );
        MatcherAssert.assertThat(
            "the provider saw the three guesses and nothing after them",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of("guess-0", "guess-1", "guess-2"))
        );
    }

    @Test
    void loginTheCacheRemembersIsRefusedOnceTheUserIsLocked() throws Exception {
        final Authentication chain = this.settings().authz().authentication();
        MatcherAssert.assertThat(
            "the right password signs in and is remembered",
            new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD)
                .user().isPresent(),
            new IsEqual<>(true)
        );
        for (int idx = 0; idx < 3; idx += 1) {
            new PasswordCheck(chain).now("alice", "guess-" + idx);
        }
        MatcherAssert.assertThat(
            "a locked user's remembered login is refused too",
            new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD)
                .refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("account_locked"))
        );
    }

    @Test
    void rememberedLoginEndsTheCountLikeAnyOtherSuccess() throws Exception {
        // Two typos, the right password, two typos, the right password:
        // nobody is locked, because every success starts the count again.
        // The right password is one the credential cache remembers.
        final Authentication chain = this.settings().authz().authentication();
        new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD);
        for (int round = 0; round < 2; round += 1) {
            new PasswordCheck(chain).now("alice", "typo-a" + round);
            new PasswordCheck(chain).now("alice", "typo-b" + round);
            MatcherAssert.assertThat(
                "round " + round + ": the right password signs in",
                new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD)
                    .user().isPresent(),
                new IsEqual<>(true)
            );
        }
    }

    @Test
    void lockLimitedToPasswordsStillRefusesARememberedPassword() throws Exception {
        FailedLoginsChainTest.policy(Map.of("failed_logins_lock_all_credentials", "false"));
        final Authentication chain = this.settings().authz().authentication();
        new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD);
        for (int idx = 0; idx < 3; idx += 1) {
            new PasswordCheck(chain).now("alice", "guess-" + idx);
        }
        MatcherAssert.assertThat(
            new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD)
                .refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("account_locked"))
        );
    }

    @Test
    void unlockedUserSignsInAgain() throws Exception {
        final YamlSettings settings = this.settings();
        final Authentication chain = settings.authz().authentication();
        for (int idx = 0; idx < 3; idx += 1) {
            new PasswordCheck(chain).now("alice", "guess-" + idx);
        }
        final FailedLogins logins = settings.failedLogins().orElseThrow();
        MatcherAssert.assertThat(
            "the settings hand out the failed logins of the chain",
            logins.unlock("alice", "admin"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and the user signs in",
            new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD)
                .user().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void rejectedKeyIsNotAFailedLoginByDefault() throws Exception {
        final Authentication chain = this.settings().authz().authentication();
        for (int idx = 0; idx < 5; idx += 1) {
            new PasswordCheck(chain).now("alice", FailedLoginsChainTest.STALE_KEY);
        }
        MatcherAssert.assertThat(
            new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD)
                .user().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void rejectedKeysLockWhenThePolicyCountsThem() throws Exception {
        FailedLoginsChainTest.policy(Map.of("failed_logins_count_rejected_keys", "true"));
        final Authentication chain = this.settings().authz().authentication();
        for (int idx = 0; idx < 3; idx += 1) {
            new PasswordCheck(chain).now("alice", FailedLoginsChainTest.STALE_KEY);
        }
        MatcherAssert.assertThat(
            "three rejected keys lock the user",
            new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD)
                .refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("account_locked"))
        );
        MatcherAssert.assertThat(
            "and no key or later password reached the password provider",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of())
        );
    }

    @Test
    void delayedLoginReachesNoProvider() throws Exception {
        FailedLoginsChainTest.policy(
            Map.of("failed_logins_delay_from_failure", "1", "failed_logins_lock_after", "0")
        );
        final Authentication chain = this.settings().authz().authentication();
        new PasswordCheck(chain).now("alice", "guess");
        final PasswordCheck.Outcome outcome =
            new PasswordCheck(chain).now("alice", RecordingAuthFactory.PASSWORD);
        MatcherAssert.assertThat(
            "the login after a failure is delayed",
            outcome.refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("login_delayed"))
        );
        MatcherAssert.assertThat(
            "and the provider saw only the failed one",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of("guess"))
        );
    }

    /**
     * Install the policy: no delay, so a test need not wait, and the lock
     * after three failures, unless the rows say otherwise.
     * @param rows Settings rows
     */
    private static void policy(final Map<String, String> rows) {
        final Map<String, String> all = new HashMap<>(
            Map.of("failed_logins_delay_from_failure", "0")
        );
        all.putAll(rows);
        FailedLoginsSettingsLoader.install(
            FakeAuthSettings.withRows(all), Map.<String, String>of()::get
        );
    }

    private YamlSettings settings() throws Exception {
        return new YamlSettings(
            Yaml.createYamlInput(
                String.join("\n", "meta:", "  credentials:", "    - type: recording")
            ).readYamlMapping(),
            this.temp
        );
    }
}
