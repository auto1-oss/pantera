/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
