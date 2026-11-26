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

import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.metrics.MicrometerMetrics;
import java.util.Collection;
import java.util.Optional;

/**
 * A password provider that asks an external identity provider, with every
 * check counted ({@code pantera.auth.provider.checks}, by provider and
 * outcome). The counter shows how many credentials leave Pantera for the
 * identity provider, and with which result:
 *
 * <ul>
 *   <li>{@code accepted} - the provider signed the user in;</li>
 *   <li>{@code rejected} - the provider said the credentials are wrong: a
 *   failed login of that user at the identity provider;</li>
 *   <li>{@code refused} - the provider refused for a reason of its own (a
 *   second factor was asked for, the directory account is locked);</li>
 *   <li>{@code unavailable} - the identity provider could not be asked.</li>
 * </ul>
 *
 * <p>A check a domain rule keeps away from the provider never arrives
 * here, and is not counted.</p>
 *
 * @since 2.2.15
 */
public final class CountedProvider implements Authentication {

    /**
     * The provider.
     */
    private final Authentication origin;

    /**
     * Its type, the {@code provider} tag.
     */
    private final String type;

    /**
     * Ctor.
     *
     * @param origin The provider
     * @param type Its type: {@code okta}, {@code keycloak}, {@code github}
     */
    public CountedProvider(final Authentication origin, final String type) {
        this.origin = origin;
        this.type = type;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        final Optional<AuthUser> res;
        try {
            res = this.origin.user(username, password);
        } catch (final RuntimeException | Error err) {
            this.count("unavailable");
            throw err;
        }
        if (res.isPresent()) {
            this.count("accepted");
        } else {
            final Optional<AuthRefusal> refusal = AuthRefusal.noted();
            if (refusal.isEmpty()) {
                this.count("rejected");
            } else if (refusal.get().status() == RsStatus.SERVICE_UNAVAILABLE) {
                this.count("unavailable");
            } else {
                this.count("refused");
            }
        }
        return res;
    }

    @Override
    public boolean canHandle(final String username) {
        return this.origin.canHandle(username);
    }

    @Override
    public Collection<String> userDomains() {
        return this.origin.userDomains();
    }

    @Override
    public boolean isAuthoritative(final String username) {
        return this.origin.isAuthoritative(username);
    }

    @Override
    public String toString() {
        return this.origin.toString();
    }

    private void count(final String outcome) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordProviderCheck(this.type, outcome);
        }
    }
}
