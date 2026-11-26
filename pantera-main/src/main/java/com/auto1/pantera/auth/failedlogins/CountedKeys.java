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
package com.auto1.pantera.auth.failedlogins;

import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.LoginSource;
import java.util.Optional;

/**
 * The API key provider, with rejected keys counted as failed logins when
 * the policy says so ({@code failed_logins_count_rejected_keys}).
 *
 * <p>A rejected key counts for the user name it was sent with. An accepted
 * key forgets no failures: a build that runs with a valid key would
 * otherwise keep clearing the count of an attack on its user. Keys are not
 * delayed; a locked user's key is stopped by {@link LockedOut}.</p>
 *
 * @since 2.2.15
 */
public final class CountedKeys implements Authentication {

    /**
     * API key provider.
     */
    private final Authentication origin;

    /**
     * Failed logins.
     */
    private final FailedLogins logins;

    /**
     * Ctor.
     *
     * @param origin API key provider
     * @param logins Failed logins
     */
    public CountedKeys(final Authentication origin, final FailedLogins logins) {
        this.origin = origin;
        this.logins = logins;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        final Optional<AuthUser> res = this.origin.user(username, password);
        if (res.isEmpty() && AuthRefusal.noted().isEmpty()
            && this.logins.policy().countRejectedKeys()) {
            final LoginSource source = LoginSource.current();
            final Admission after = this.logins.failed(
                username,
                new LoginOrigin(source.clientIp(), source.userAgent(), source.path(), "api_key")
            );
            if (after.kind() == Admission.Kind.LOCKED) {
                this.logins.refuse(after);
            }
        }
        return res;
    }

    @Override
    public String toString() {
        return String.format("%s(%s)", this.getClass().getSimpleName(), this.origin);
    }
}
