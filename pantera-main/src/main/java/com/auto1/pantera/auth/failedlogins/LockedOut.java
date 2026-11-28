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

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import java.util.Optional;

/**
 * Gate in front of the sign-in chain: a locked user can use no credential.
 * A password and an API key of a locked name are refused with 403 and
 * "Account locked", and nothing behind the gate is asked.
 *
 * <p>The gate asks {@link FailedLogins#locked(String)}, which answers from
 * memory: it stands in the path of every login.</p>
 *
 * @since 2.2.15
 */
public final class LockedOut implements Authentication {

    /**
     * Sign-in chain.
     */
    private final Authentication origin;

    /**
     * Failed logins.
     */
    private final FailedLogins logins;

    /**
     * Ctor.
     *
     * @param origin Sign-in chain
     * @param logins Failed logins
     */
    public LockedOut(final Authentication origin, final FailedLogins logins) {
        this.origin = origin;
        this.logins = logins;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        final Optional<AuthUser> res;
        if (this.logins.locked(username)) {
            this.logins.refuse(Admission.LOCKED, username);
            res = Optional.empty();
        } else {
            res = this.origin.user(username, password);
        }
        return res;
    }

    @Override
    public String toString() {
        return String.format("%s(%s)", this.getClass().getSimpleName(), this.origin);
    }
}
