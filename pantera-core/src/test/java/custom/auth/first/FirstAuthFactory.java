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
package custom.auth.first;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.auth.PanteraAuthFactory;
import com.auto1.pantera.http.auth.AuthFactory;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;

import java.util.Optional;

/**
 * Test auth.
 * @since 1.3
 */
@PanteraAuthFactory("first")
public final class FirstAuthFactory implements AuthFactory {

    @Override
    public Authentication getAuthentication(final YamlMapping conf) {
        return new FirstAuth();
    }

    public static class FirstAuth implements Authentication {
        @Override
        public Optional<AuthUser> user(String username, String password) {
            return Optional.empty();
        }
    }
}
