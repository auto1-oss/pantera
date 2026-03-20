/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.http.filter.Filters;
import java.util.Optional;

/**
 * Cache for filters.
 * @since 0.28
 */
public interface FiltersCache extends Cleanable<String> {
    /**
     * Finds filters by specified in settings configuration cache or creates
     * a new item and caches it.
     *
     * @param reponame Repository full name
     * @param repoyaml Repository yaml configuration
     * @return Filters defined in yaml configuration
     */
    Optional<Filters> filters(String reponame, YamlMapping repoyaml);

    /**
     * Returns the approximate number of entries in this cache.
     *
     * @return Number of entries
     */
    long size();
}
