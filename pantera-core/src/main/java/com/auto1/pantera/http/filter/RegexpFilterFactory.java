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
package com.auto1.pantera.http.filter;

import com.amihaiemil.eoyaml.YamlMapping;

/**
 * RegExp filter factory.
 *
 * @since 1.2
 */
@PanteraFilterFactory("regexp")
public final class RegexpFilterFactory implements FilterFactory {
    @Override
    public Filter newFilter(final YamlMapping yaml) {
        return new RegexpFilter(yaml);
    }
}
