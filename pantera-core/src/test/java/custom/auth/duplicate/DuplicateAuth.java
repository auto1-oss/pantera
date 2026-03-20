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
package custom.auth.duplicate;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.auth.PanteraAuthFactory;
import com.auto1.pantera.http.auth.AuthFactory;
import com.auto1.pantera.http.auth.Authentication;

import java.util.Optional;

/**
 * Test auth.
 */
@PanteraAuthFactory("first")
public final class DuplicateAuth implements AuthFactory {

    @Override
    public Authentication getAuthentication(final YamlMapping conf) {
        return (username, password) -> Optional.empty();
    }
}
