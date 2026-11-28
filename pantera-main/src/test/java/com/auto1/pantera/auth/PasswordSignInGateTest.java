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

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.settings.cache.CachedUsers;
import com.auto1.pantera.settings.policy.PasswordSignInConfig;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A password reaches an identity provider only where the policy lets it.
 *
 * @since 2.2.15
 */
final class PasswordSignInGateTest {

    /**
     * A login that comes with a repository request.
     */
    private static final LoginSource REPOSITORY =
        new LoginSource("10.0.0.1", "Apache-Maven/3.9", LoginSource.REPOSITORY);

    /**
     * A login from the sign-in form.
     */
    private static final LoginSource FORM = new LoginSource("10.0.0.1", "Mozilla/5.0", "ui");

    /**
     * Passwords the provider was asked to check.
     */
    private AtomicInteger asked;

    /**
     * The policy in force.
     */
    private AtomicReference<PasswordSignInConfig> policy;

    /**
     * The gated provider: accepts every password.
     */
    private Authentication gate;

    @BeforeEach
    void init() {
        this.asked = new AtomicInteger();
        this.policy = new AtomicReference<>(PasswordSignInConfig.defaults());
        this.gate = new PasswordSignInGate(
            new Authentication() {
                @Override
                public Optional<AuthUser> user(final String name, final String pass) {
                    PasswordSignInGateTest.this.asked.incrementAndGet();
                    return Optional.of(new AuthUser(name, "okta"));
                }

                @Override
                public boolean canHandle(final String name) {
                    return true;
                }

                @Override
                public boolean isAuthoritative(final String name) {
                    return true;
                }
            },
            "okta", this.policy::get
        );
    }

    @Test
    void passwordOfARepositoryRequestReachesTheProviderByDefault() {
        MatcherAssert.assertThat(
            "the login is accepted",
            this.login(this.gate, PasswordSignInGateTest.REPOSITORY).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat("by the provider", this.asked.get(), new IsEqual<>(1));
    }

    @Test
    void formOnlyKeepsThePasswordOfARepositoryRequestFromTheProvider() {
        this.policy.set(new PasswordSignInConfig(Map.of("okta", "form")));
        MatcherAssert.assertThat(
            "the repository login is refused",
            this.login(this.gate, PasswordSignInGateTest.REPOSITORY).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat("without asking the provider", this.asked.get(), new IsEqual<>(0));
        MatcherAssert.assertThat(
            "the form login is accepted",
            this.login(this.gate, PasswordSignInGateTest.FORM).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat("by the provider", this.asked.get(), new IsEqual<>(1));
    }

    @Test
    void offKeepsEveryPasswordFromTheProvider() {
        this.policy.set(new PasswordSignInConfig(Map.of("okta", "off")));
        MatcherAssert.assertThat(
            "the form login is refused",
            this.login(this.gate, PasswordSignInGateTest.FORM).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "and so is the repository login",
            this.login(this.gate, PasswordSignInGateTest.REPOSITORY).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat("the provider was never asked", this.asked.get(), new IsEqual<>(0));
    }

    @Test
    void closedGateDoesNotClaimTheUser() {
        this.policy.set(new PasswordSignInConfig(Map.of("okta", "off")));
        MatcherAssert.assertThat(
            "the provider does not handle the name",
            this.gate.canHandle("jane@example.com"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "and is not the last word on it: the chain goes on",
            this.gate.isAuthoritative("jane@example.com"), new IsEqual<>(false)
        );
    }

    @Test
    void policyAppliesToTheNextLoginWithoutARestart() {
        this.login(this.gate, PasswordSignInGateTest.REPOSITORY);
        this.policy.set(new PasswordSignInConfig(Map.of("okta", "off")));
        this.login(this.gate, PasswordSignInGateTest.REPOSITORY);
        this.policy.set(PasswordSignInConfig.defaults());
        this.login(this.gate, PasswordSignInGateTest.REPOSITORY);
        MatcherAssert.assertThat(this.asked.get(), new IsEqual<>(2));
    }

    @Test
    void loginRememberedFromTheFormDoesNotAnswerARepositoryRequest() {
        this.policy.set(new PasswordSignInConfig(Map.of("okta", "form")));
        final CachedUsers cached = new CachedUsers(this.gate);
        cached.scopedBy(() -> this.policy.get().scope(LoginSource.current()));
        MatcherAssert.assertThat(
            "the form login is accepted and remembered",
            this.login(cached, PasswordSignInGateTest.FORM).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the same password with a repository request is refused",
            this.login(cached, PasswordSignInGateTest.REPOSITORY).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the form login again is accepted",
            this.login(cached, PasswordSignInGateTest.FORM).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the provider was asked for each form login: a person signs in there, and a"
                + " remembered login would skip the second factor",
            this.asked.get(), new IsEqual<>(2)
        );
    }

    @Test
    void loginRememberedBeforeThePolicyChangedIsNotServedAfterIt() {
        final CachedUsers cached = new CachedUsers(this.gate);
        cached.scopedBy(() -> this.policy.get().scope(LoginSource.current()));
        this.login(cached, PasswordSignInGateTest.REPOSITORY);
        this.policy.set(new PasswordSignInConfig(Map.of("okta", "off")));
        MatcherAssert.assertThat(
            this.login(cached, PasswordSignInGateTest.REPOSITORY).isPresent(),
            new IsEqual<>(false)
        );
    }

    private Optional<AuthUser> login(final Authentication auth, final LoginSource source) {
        return new PasswordCheck(auth, source).now("jane@example.com", "secret").user();
    }
}
