/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.auth;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.log.EcsLogger;
import java.io.IOException;
import java.util.Optional;

/**
 * Authentication based on Okta OIDC (Authorization Code flow with sessionToken).
 */
public final class AuthFromOkta implements Authentication {

    private final OktaOidcClient client;

    private final OktaUserProvisioning provisioning;

    public AuthFromOkta(final OktaOidcClient client,
        final OktaUserProvisioning provisioning) {
        this.client = client;
        this.provisioning = provisioning;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        Optional<AuthUser> res = Optional.empty();
        try {
            final String mfaCode = OktaAuthContext.mfaCode();
            final OktaOidcClient.OktaAuthResult okta = this.client.authenticate(
                username, password, mfaCode
            );
            if (okta != null) {
                this.provisioning.provision(okta.username(), okta.email(), okta.groups());
                res = Optional.of(new AuthUser(okta.username(), "okta"));
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            EcsLogger.error("com.auto1.pantera.auth")
                .message("Okta authentication interrupted")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .error(interrupted)
                .log();
        } catch (final IOException err) {
            EcsLogger.error("com.auto1.pantera.auth")
                .message("Okta authentication failed")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .error(err)
                .log();
        }
        return res;
    }

    @Override
    public String toString() {
        return String.format("%s()", this.getClass().getSimpleName());
    }
}
