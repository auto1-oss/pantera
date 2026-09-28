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

import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for SSO identity binding (SecOps sso-oidc,
 * identity-confusion finding).
 *
 * <p>Before 2.2.9 an SSO login was keyed solely by the mutable
 * {@code preferred_username} claim and upserted into the global users
 * row of that name, so an IdP account (from any provider) whose username
 * collided with an existing local or other-provider account silently
 * inherited that account's roles. Identity must be bound to
 * {@code (provider, issuer, subject)}.</p>
 *
 * @since 2.2.9
 */
final class SsoIdentityBindingTest {

    private static final String OKTA = "okta";
    private static final String ISS = "https://idp.example.com";

    @Test
    void newUserIsProvisionedWithSubject() {
        final SsoIdentityBinding.Decision d = SsoIdentityBinding.resolve(
            Optional.empty(), OKTA, ISS, "sub-1"
        );
        MatcherAssert.assertThat(
            "a brand-new SSO user is provisioned", d.kind(),
            new IsEqual<>(SsoIdentityBinding.Kind.PROVISION)
        );
        MatcherAssert.assertThat(
            "the binding subject is (provider|iss|sub)", d.subject(),
            new IsEqual<>("okta|https://idp.example.com|sub-1")
        );
    }

    @Test
    void sameSubjectIsAccepted() {
        final JsonObject existing = user(OKTA, "okta|https://idp.example.com|sub-1");
        MatcherAssert.assertThat(
            "the same bound identity logs in normally",
            SsoIdentityBinding.resolve(Optional.of(existing), OKTA, ISS, "sub-1").kind(),
            new IsEqual<>(SsoIdentityBinding.Kind.ACCEPT)
        );
    }

    @Test
    void usernameCollisionFromDifferentSubjectIsRejected() {
        // Existing 'admin' row is bound to subject sub-1; an IdP account
        // with the same preferred_username but a DIFFERENT sub must not
        // inherit it.
        final JsonObject existing = user(OKTA, "okta|https://idp.example.com|sub-1");
        MatcherAssert.assertThat(
            "a different IdP subject claiming an existing username must be rejected",
            SsoIdentityBinding.resolve(Optional.of(existing), OKTA, ISS, "sub-999").kind(),
            new IsEqual<>(SsoIdentityBinding.Kind.REJECT)
        );
    }

    @Test
    void ssoLoginCannotTakeOverLocalPasswordAccount() {
        // A local (password) account with no SSO binding: an IdP user with
        // the same username must NOT be merged into it.
        final JsonObject local = Json.createObjectBuilder()
            .add("name", "admin").add("auth_provider", "local").add("enabled", true).build();
        MatcherAssert.assertThat(
            "an SSO login must not take over an existing local password account of the same name",
            SsoIdentityBinding.resolve(Optional.of(local), OKTA, ISS, "sub-1").kind(),
            new IsEqual<>(SsoIdentityBinding.Kind.REJECT)
        );
    }

    @Test
    void otherProviderAccountIsRejected() {
        final JsonObject keycloakUser = user("keycloak", "keycloak|https://kc.example.com|sub-7");
        MatcherAssert.assertThat(
            "an account bound to another provider must not be claimed via this provider",
            SsoIdentityBinding.resolve(Optional.of(keycloakUser), OKTA, ISS, "sub-7").kind(),
            new IsEqual<>(SsoIdentityBinding.Kind.REJECT)
        );
    }

    @Test
    void legacySameProviderUserIsBoundOnFirstLogin() {
        // Pre-2.2.9 SSO users of THIS provider have no stored subject yet;
        // their first post-upgrade login binds it (the IdP already
        // authenticated them for this provider).
        final JsonObject legacy = Json.createObjectBuilder()
            .add("name", "alice").add("auth_provider", OKTA).add("enabled", true).build();
        final SsoIdentityBinding.Decision d =
            SsoIdentityBinding.resolve(Optional.of(legacy), OKTA, ISS, "sub-1");
        MatcherAssert.assertThat(
            "a legacy same-provider user is bound on first login", d.kind(),
            new IsEqual<>(SsoIdentityBinding.Kind.BIND)
        );
    }

    private static JsonObject user(final String provider, final String subject) {
        return Json.createObjectBuilder()
            .add("name", "admin").add("auth_provider", provider)
            .add("sso_subject", subject).add("enabled", true).build();
    }
}
