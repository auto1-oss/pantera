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

import com.auto1.pantera.auth.signin.RejectedKey;
import com.auto1.pantera.auth.signin.SignInFailures;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.LoginSource;
import java.util.Optional;

/**
 * The API key provider, with rejected keys counted as failed logins when
 * the policy says so ({@code failed_logins_count_rejected_keys}).
 *
 * <p>A rejected key counts for the user name it was sent with, by the rule
 * of {@link FailedLogins#keyRejected}, which also counts a key sent as a
 * Bearer token. An accepted key forgets no failures: a build that runs with
 * a valid key would otherwise keep clearing the count of an attack on its
 * user. Keys are not delayed; a locked user's key is stopped by
 * {@link LockedOut}.</p>
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
     * Sign-in failures report, null when none is kept.
     */
    private final SignInFailures report;

    /**
     * Ctor.
     *
     * @param origin API key provider
     * @param logins Failed logins
     */
    public CountedKeys(final Authentication origin, final FailedLogins logins) {
        this(origin, logins, null);
    }

    /**
     * Ctor.
     *
     * @param origin API key provider
     * @param logins Failed logins
     * @param report Sign-in failures report, null when none is kept
     */
    public CountedKeys(
        final Authentication origin, final FailedLogins logins, final SignInFailures report
    ) {
        this.origin = origin;
        this.logins = logins;
        this.report = report;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        final Optional<AuthUser> res = this.origin.user(username, password);
        // A key that was refused with a reason of its own (its user is
        // locked) is not a rejected key: that reason stands.
        if (res.isEmpty() && AuthRefusal.noted().isEmpty()) {
            final LoginSource source = LoginSource.current();
            final Optional<RejectedKey> known;
            if (this.report == null) {
                known = Optional.empty();
            } else {
                known = this.report.rejected(password, username, source);
            }
            if (!this.logins.keyRejected(username, source)) {
                // The client is told why: an expired key is replaced, a
                // password is not what is wrong.
                known.ifPresent(
                    key -> new AuthRefusal(
                        key.reason(), RsStatus.UNAUTHORIZED, 0L, key.message()
                    ).note()
                );
            }
        }
        return res;
    }

    @Override
    public String toString() {
        return String.format("%s(%s)", this.getClass().getSimpleName(), this.origin);
    }
}
