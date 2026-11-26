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
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.policy.PasswordSignInConfig;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Lets a password through to an identity provider only where the
 * password sign-in policy allows it: everywhere, on the sign-in form
 * only, or nowhere ({@link PasswordSignInConfig}).
 *
 * <p>Where the gate is closed the provider is as good as absent: it is
 * not asked, does not claim the user name, and the login goes on to the
 * rest of the chain. A login nobody accepts is a failed login like any
 * other, and is counted as one.</p>
 *
 * <p>The policy is read on every login, so a change of the setting
 * applies to the next one.</p>
 *
 * @since 2.2.15
 */
public final class PasswordSignInGate implements Authentication {

    /**
     * The provider.
     */
    private final Authentication inner;

    /**
     * Provider type.
     */
    private final String type;

    /**
     * The policy in force.
     */
    private final Supplier<PasswordSignInConfig> policy;

    /**
     * Ctor.
     *
     * @param inner The provider
     * @param type Provider type
     * @param policy The policy in force
     */
    public PasswordSignInGate(
        final Authentication inner, final String type,
        final Supplier<PasswordSignInConfig> policy
    ) {
        this.inner = inner;
        this.type = type;
        this.policy = policy;
    }

    @Override
    public Optional<AuthUser> user(final String name, final String pass) {
        final Optional<AuthUser> res;
        if (this.open()) {
            res = this.inner.user(name, pass);
        } else {
            EcsLogger.debug("com.auto1.pantera.auth")
                .message(
                    String.format(
                        "Password not sent to the %s provider: its password sign-in is '%s' and the login came by '%s'",
                        this.type, this.policy.get().mode(this.type),
                        LoginSource.current().path()
                    )
                )
                .eventCategory("authentication")
                .eventAction("password_sign_in_skipped")
                .field("event.provider", this.type)
                .field("user.name", name)
                .field("log.source", "application")
                .log();
            res = Optional.empty();
        }
        return res;
    }

    @Override
    public boolean canHandle(final String username) {
        return this.open() && this.inner.canHandle(username);
    }

    @Override
    public Collection<String> userDomains() {
        return this.inner.userDomains();
    }

    @Override
    public boolean isAuthoritative(final String username) {
        return this.open() && this.inner.isAuthoritative(username);
    }

    @Override
    public String toString() {
        return String.format("PasswordSignInGate(type=%s, inner=%s)", this.type, this.inner);
    }

    private boolean open() {
        return this.policy.get().allows(this.type, LoginSource.current());
    }
}
