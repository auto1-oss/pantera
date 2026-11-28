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

import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the Okta password provider does when Okta asks for a second factor.
 *
 * <p>A repository login has nobody to answer a push or type a code: it is
 * refused with the reason {@code mfa_required}, and no factor is verified.
 * A sign-in from the form keeps the push and the code.</p>
 *
 * @since 2.2.15
 */
final class OktaSecondFactorTest {

    @BeforeEach
    @AfterEach
    void clearThread() {
        OktaAuthContext.clear();
        AuthRefusal.take();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "MFA_REQUIRED", "MFA_CHALLENGE", "MFA_ENROLL", "MFA_ENROLL_ACTIVATE"
    })
    void repositoryLoginStartsNoPush(final String status) throws Exception {
        try (FakeOkta okta = new FakeOkta(200, status)) {
            final Optional<AuthUser> user = OktaSecondFactorTest.provider(okta)
                .user("alice@example.com", "correct password");
            MatcherAssert.assertThat(
                "the login is refused",
                user.isPresent(), new IsEqual<>(false)
            );
            MatcherAssert.assertThat(
                "the password was checked once",
                okta.count(FakeOkta.AUTHN), new IsEqual<>(1L)
            );
            MatcherAssert.assertThat(
                "no push is sent to the user's phone",
                okta.count(FakeOkta.PUSH), new IsEqual<>(0L)
            );
            MatcherAssert.assertThat(
                "no code is verified",
                okta.count(FakeOkta.TOTP), new IsEqual<>(0L)
            );
            MatcherAssert.assertThat(
                "the refusal says a second factor was asked for",
                AuthRefusal.take().map(AuthRefusal::reason),
                new IsEqual<>(Optional.of("mfa_required"))
            );
        }
    }

    @Test
    void repositoryLoginIgnoresACodeLeftOnTheThread() throws Exception {
        try (FakeOkta okta = new FakeOkta(200, "MFA_REQUIRED")) {
            OktaAuthContext.setMfaCode("123456");
            final Optional<AuthUser> user = OktaSecondFactorTest.provider(okta)
                .user("alice@example.com", "correct password");
            MatcherAssert.assertThat(
                "the login is refused",
                user.isPresent(), new IsEqual<>(false)
            );
            MatcherAssert.assertThat(
                "a code that did not come with this login is not sent to Okta",
                okta.count(FakeOkta.TOTP), new IsEqual<>(0L)
            );
            MatcherAssert.assertThat(
                "no push is sent",
                okta.count(FakeOkta.PUSH), new IsEqual<>(0L)
            );
        }
    }

    @Test
    void signInFormWithoutCodeStartsThePush() throws Exception {
        try (FakeOkta okta = new FakeOkta(200, "MFA_REQUIRED")) {
            OktaAuthContext.setInteractive(true);
            OktaSecondFactorTest.provider(okta).user("alice@example.com", "correct password");
            MatcherAssert.assertThat(
                "the push is started for a person who can answer it",
                okta.count(FakeOkta.PUSH), new IsEqual<>(1L)
            );
            MatcherAssert.assertThat(
                "the push was not approved: the password was right, the second factor"
                    + " was not confirmed; neither a wrong password nor a repository login",
                AuthRefusal.take().map(AuthRefusal::reason),
                new IsEqual<>(Optional.of(AuthRefusal.SECOND_FACTOR_FAILED))
            );
        }
    }

    @Test
    void signInFormWithCodeVerifiesTheCode() throws Exception {
        try (FakeOkta okta = new FakeOkta(200, "MFA_REQUIRED")) {
            OktaAuthContext.setInteractive(true);
            OktaAuthContext.setMfaCode("123456");
            OktaSecondFactorTest.provider(okta).user("alice@example.com", "correct password");
            MatcherAssert.assertThat(
                "the code is verified once",
                okta.bodies(FakeOkta.TOTP).size(), new IsEqual<>(1)
            );
            MatcherAssert.assertThat(
                "with the code the person typed",
                okta.bodies(FakeOkta.TOTP).get(0), new StringContains("\"passCode\":\"123456\"")
            );
            MatcherAssert.assertThat(
                "no push is sent beside the code",
                okta.count(FakeOkta.PUSH), new IsEqual<>(0L)
            );
        }
    }

    @Test
    void passwordOnlyLoginGoesOnWithoutASecondFactor() throws Exception {
        try (FakeOkta okta = new FakeOkta(200, "SUCCESS")) {
            OktaSecondFactorTest.provider(okta).user("alice@example.com", "correct password");
            MatcherAssert.assertThat(
                "the session is exchanged at the authorization endpoint",
                okta.count(FakeOkta.AUTHORIZE), new IsEqual<>(1L)
            );
            MatcherAssert.assertThat(
                "no factor is verified",
                List.of(okta.count(FakeOkta.PUSH), okta.count(FakeOkta.TOTP)),
                new IsEqual<>(List.of(0L, 0L))
            );
            MatcherAssert.assertThat(
                "the fake refuses the session exchange; no second factor is reported",
                AuthRefusal.take().map(AuthRefusal::reason),
                new IsEqual<>(Optional.of("sign_in_not_completed"))
            );
        }
    }

    @Test
    void wrongPasswordIsNotReportedAsSecondFactor() throws Exception {
        try (FakeOkta okta = new FakeOkta(401, "")) {
            final Optional<AuthUser> user = OktaSecondFactorTest.provider(okta)
                .user("alice@example.com", "wrong password");
            MatcherAssert.assertThat(
                "the login is refused",
                user.isPresent(), new IsEqual<>(false)
            );
            MatcherAssert.assertThat(
                "a wrong password is a plain failed login",
                AuthRefusal.take().isPresent(), new IsEqual<>(false)
            );
            MatcherAssert.assertThat(
                "no factor is verified",
                List.of(okta.count(FakeOkta.PUSH), okta.count(FakeOkta.TOTP)),
                new IsEqual<>(List.of(0L, 0L))
            );
        }
    }

    @Test
    void contextIsNotInteractiveUnlessSet() {
        MatcherAssert.assertThat(
            "a thread checks repository logins by default",
            OktaAuthContext.interactive(), new IsEqual<>(false)
        );
        OktaAuthContext.setInteractive(true);
        OktaAuthContext.setMfaCode("123456");
        OktaAuthContext.clear();
        MatcherAssert.assertThat(
            "clearing takes the form sign-in off the thread",
            OktaAuthContext.interactive(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "and its code",
            OktaAuthContext.mfaCode() == null, new IsEqual<>(true)
        );
    }

    /**
     * Okta password provider that talks to the fake.
     *
     * @param okta Fake Okta
     * @return Provider; it provisions nobody, no login here succeeds
     */
    private static AuthFromOkta provider(final FakeOkta okta) {
        return new AuthFromOkta(
            new OktaOidcClient(
                okta.url(), null, null, null, "client", "secret", null, null, null
            ),
            null
        );
    }
}
