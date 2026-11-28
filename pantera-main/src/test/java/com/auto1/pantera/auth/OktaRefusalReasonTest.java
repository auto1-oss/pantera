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
package com.auto1.pantera.auth;

import com.auto1.pantera.auth.failedlogins.FailedLogins;
import com.auto1.pantera.auth.failedlogins.GuardedPasswords;
import com.auto1.pantera.auth.failedlogins.InMemoryFailedLoginStore;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A login the Okta provider refuses is a wrong password only when Okta
 * says so. Everything else - Okta cannot be reached, answers with an
 * error, reports the account locked or the password expired, or the
 * sign-in fails after the password was accepted - is refused with a reason
 * of its own and never counted as a failed login.
 *
 * @since 2.2.15
 */
final class OktaRefusalReasonTest {

    @BeforeEach
    @AfterEach
    void clearThread() {
        OktaAuthContext.clear();
        AuthRefusal.take();
    }

    @Test
    void wrongPasswordHasNoReasonOfItsOwn() throws Exception {
        try (FakeOkta okta = new FakeOkta(401, "")) {
            MatcherAssert.assertThat(
                OktaRefusalReasonTest.login(okta).refusal().isPresent(), new IsEqual<>(false)
            );
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503})
    void errorAnswerOfOktaIsAnOutage(final int status) throws Exception {
        try (FakeOkta okta = new FakeOkta(status, "")) {
            final AuthRefusal refusal = OktaRefusalReasonTest.login(okta).refusal().orElseThrow();
            MatcherAssert.assertThat(
                "the reason names the identity provider",
                refusal.reason(), new IsEqual<>("identity_provider_unavailable")
            );
            MatcherAssert.assertThat(
                "the client is told to come back, not that its password is wrong",
                refusal.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
            );
        }
    }

    @Test
    void unreachableOktaIsAnOutage() throws Exception {
        final FakeOkta okta = new FakeOkta(200, "SUCCESS");
        okta.close();
        MatcherAssert.assertThat(
            OktaRefusalReasonTest.login(okta).refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("identity_provider_unavailable"))
        );
    }

    @ParameterizedTest
    @CsvSource({
        "LOCKED_OUT, directory_account_locked",
        "PASSWORD_EXPIRED, password_expired",
        "PASSWORD_WARN, sign_in_not_completed",
        "SOMETHING_NEW, sign_in_not_completed"
    })
    void statusThatIsNotAWrongPasswordIsNamed(final String status, final String reason)
        throws Exception {
        try (FakeOkta okta = new FakeOkta(200, status)) {
            MatcherAssert.assertThat(
                OktaRefusalReasonTest.login(okta).refusal().map(AuthRefusal::reason),
                new IsEqual<>(Optional.of(reason))
            );
        }
    }

    @Test
    void failureAfterAnAcceptedPasswordIsNotAWrongPassword() throws Exception {
        try (FakeOkta okta = new FakeOkta(200, "SUCCESS")) {
            MatcherAssert.assertThat(
                "the fake refuses the session exchange: the password itself was right",
                OktaRefusalReasonTest.login(okta).refusal().map(AuthRefusal::reason),
                new IsEqual<>(Optional.of("sign_in_not_completed"))
            );
        }
    }

    @Test
    void outageOfOktaLocksNobody() throws Exception {
        try (FakeOkta okta = new FakeOkta(503, "")) {
            final FailedLogins logins = OktaRefusalReasonTest.logins();
            final Authentication guarded =
                new GuardedPasswords(OktaRefusalReasonTest.provider(okta), logins);
            for (int idx = 0; idx < 10; idx += 1) {
                new PasswordCheck(guarded).now("alice@example.com", "correct password");
            }
            MatcherAssert.assertThat(
                "no failed login is counted",
                logins.state("alice@example.com").failures(), new IsEqual<>(0)
            );
            MatcherAssert.assertThat(
                "and every login was tried at Okta",
                okta.count(FakeOkta.AUTHN), new IsEqual<>(10L)
            );
        }
    }

    @Test
    void wrongPasswordsAtOktaLockTheUserAndStopReachingOkta() throws Exception {
        try (FakeOkta okta = new FakeOkta(401, "")) {
            final FailedLogins logins = OktaRefusalReasonTest.logins();
            final Authentication guarded =
                new GuardedPasswords(OktaRefusalReasonTest.provider(okta), logins);
            for (int idx = 0; idx < 10; idx += 1) {
                new PasswordCheck(guarded).now("alice@example.com", "guess-" + idx);
            }
            MatcherAssert.assertThat(
                "the user is locked",
                logins.state("alice@example.com").locked(), new IsEqual<>(true)
            );
            MatcherAssert.assertThat(
                "Okta, and the directory behind it, saw three failed logins of ten",
                okta.count(FakeOkta.AUTHN), new IsEqual<>(3L)
            );
        }
    }

    private static PasswordCheck.Outcome login(final FakeOkta okta) {
        return new PasswordCheck(OktaRefusalReasonTest.provider(okta))
            .now("alice@example.com", "any password");
    }

    private static FailedLogins logins() {
        return new FailedLogins(
            new InMemoryFailedLoginStore(), () -> new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false)
        );
    }

    private static AuthFromOkta provider(final FakeOkta okta) {
        return new AuthFromOkta(
            new OktaOidcClient(
                okta.url(), null, null, null, "client", "secret", null, null, null
            ),
            null
        );
    }
}
