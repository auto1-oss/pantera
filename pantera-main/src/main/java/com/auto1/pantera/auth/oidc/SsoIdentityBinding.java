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
import javax.json.JsonObject;

/**
 * Binds an SSO login to a stable external identity —
 * {@code (provider, issuer, subject)} — instead of the mutable
 * {@code preferred_username} claim.
 *
 * <p>Before 2.2.9 an SSO login upserted the global users row named by the
 * IdP username, so an IdP account whose username collided with an
 * existing local or other-provider account silently inherited that
 * account's roles. The decision here is fail-closed: an existing account
 * is only accepted by the exact identity it was bound to, or bound on
 * first post-upgrade login when it is a legacy SSO account of the same
 * provider (the IdP already authenticated it for that provider).</p>
 *
 * @since 2.2.9
 */
public final class SsoIdentityBinding {

    /**
     * Users-row key holding the bound external identity.
     */
    public static final String SUBJECT_KEY = "sso_subject";

    /**
     * Outcome kinds.
     */
    public enum Kind {
        /** No such user yet: provision and bind. */
        PROVISION,
        /** Existing user bound to exactly this identity. */
        ACCEPT,
        /** Legacy same-provider user without a binding: bind now. */
        BIND,
        /** Username collision with a different identity: refuse. */
        REJECT
    }

    /**
     * Binding decision.
     * @param kind Outcome
     * @param subject Canonical bound identity {@code provider|iss|sub}
     * @param reason Server-side reason (never shown to the client)
     */
    public record Decision(Kind kind, String subject, String reason) {
    }

    private SsoIdentityBinding() {
    }

    /**
     * Decide how an SSO login relates to the existing user of that username.
     *
     * @param existing Existing users row for the username, if any
     * @param provider Provider type the login came through (e.g. okta)
     * @param issuer Verified {@code iss} claim
     * @param sub Verified {@code sub} claim
     * @return Decision
     */
    public static Decision resolve(
        final Optional<JsonObject> existing,
        final String provider,
        final String issuer,
        final String sub
    ) {
        final String subject = provider + "|" + issuer + "|" + sub;
        if (existing.isEmpty()) {
            return new Decision(Kind.PROVISION, subject, "new user");
        }
        final JsonObject user = existing.get();
        final String bound = user.containsKey(SUBJECT_KEY) && !user.isNull(SUBJECT_KEY)
            ? user.getString(SUBJECT_KEY) : null;
        if (bound != null) {
            if (bound.equals(subject)) {
                return new Decision(Kind.ACCEPT, subject, "same bound identity");
            }
            return new Decision(
                Kind.REJECT, subject, "username is bound to a different SSO identity"
            );
        }
        final String owner = user.getString("auth_provider", "");
        if (provider.equals(owner)) {
            return new Decision(Kind.BIND, subject, "legacy same-provider user: binding");
        }
        return new Decision(
            Kind.REJECT, subject,
            "username belongs to a '" + owner + "' account; SSO must not take it over"
        );
    }
}
