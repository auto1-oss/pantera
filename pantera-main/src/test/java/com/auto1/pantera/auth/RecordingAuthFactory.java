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

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.auth.AuthFactory;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.PanteraAuthFactory;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only password provider of type {@code recording}. It stands for a
 * provider that hands the password to an external identity provider: every
 * credential it is asked about is recorded, so a test can prove which
 * credentials would have left Pantera.
 *
 * @since 2.2.15
 */
@PanteraAuthFactory("recording")
public final class RecordingAuthFactory implements AuthFactory {

    /**
     * The one password this provider accepts, for any user name.
     */
    static final String PASSWORD = "correct horse battery staple";

    /**
     * Passwords this provider was asked about, in order.
     */
    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    @Override
    public Authentication getAuthentication(final YamlMapping conf) {
        return (name, pass) -> {
            RecordingAuthFactory.SEEN.add(String.valueOf(pass));
            final Optional<AuthUser> res;
            if (RecordingAuthFactory.PASSWORD.equals(pass)) {
                res = Optional.of(new AuthUser(name, "recording"));
            } else {
                res = Optional.empty();
            }
            return res;
        };
    }
}
