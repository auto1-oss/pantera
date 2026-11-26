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

import com.auto1.pantera.http.auth.LoginSource;
import java.util.Map;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Where a password may be handed to an identity provider.
 *
 * @since 2.2.15
 */
final class PasswordSignInConfigTest {

    /**
     * A login that comes with a repository request.
     */
    private static final LoginSource REPOSITORY =
        new LoginSource("10.0.0.1", "Apache-Maven/3.9", LoginSource.REPOSITORY);

    /**
     * A login from the sign-in form.
     */
    private static final LoginSource FORM = new LoginSource("10.0.0.1", "Mozilla/5.0", "ui");

    @ParameterizedTest
    @CsvSource({
        "all,repository,true", "all,ui,true", "all,api,true", "all,'',true",
        "form,repository,false", "form,ui,true", "form,api,true", "form,'',false",
        "off,repository,false", "off,ui,false", "off,api,false", "off,'',false"
    })
    void modeDecidesByWhereTheLoginComesFrom(
        final String mode, final String path, final boolean allowed
    ) {
        MatcherAssert.assertThat(
            new PasswordSignInConfig(Map.of("okta", mode))
                .allows("okta", new LoginSource("", "", path)),
            new IsEqual<>(allowed)
        );
    }

    @Test
    void providerWithoutAModeTakesPasswordsEverywhere() {
        MatcherAssert.assertThat(
            "the defaults",
            PasswordSignInConfig.defaults().allows("okta", PasswordSignInConfigTest.REPOSITORY),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a provider the policy does not name",
            new PasswordSignInConfig(Map.of("okta", "off"))
                .allows("keycloak", PasswordSignInConfigTest.REPOSITORY),
            new IsEqual<>(true)
        );
    }

    @Test
    void unknownModeIsRefused() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new PasswordSignInConfig(Map.of("okta", "sometimes"))
        );
    }

    @Test
    void unknownProviderIsRefused() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new PasswordSignInConfig(Map.of("ldap", "off"))
        );
    }

    @Test
    void unrestrictedPolicyLeavesRememberedLoginsAsTheyWere() {
        MatcherAssert.assertThat(
            PasswordSignInConfig.defaults().scope(PasswordSignInConfigTest.REPOSITORY),
            new IsEqual<>("")
        );
    }

    @Test
    void restrictedPolicyKeepsFormAndRepositoryLoginsApart() {
        final PasswordSignInConfig form = new PasswordSignInConfig(Map.of("okta", "form"));
        MatcherAssert.assertThat(
            "a login remembered from the form does not answer a repository request",
            form.scope(PasswordSignInConfigTest.FORM),
            new IsNot<>(new IsEqual<>(form.scope(PasswordSignInConfigTest.REPOSITORY)))
        );
        MatcherAssert.assertThat(
            "and a login remembered under one policy does not answer under another",
            form.scope(PasswordSignInConfigTest.FORM),
            new IsNot<>(
                new IsEqual<>(
                    new PasswordSignInConfig(Map.of("okta", "off"))
                        .scope(PasswordSignInConfigTest.FORM)
                )
            )
        );
    }
}
