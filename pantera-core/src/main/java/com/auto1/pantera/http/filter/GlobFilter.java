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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RequestLine;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;

/**
 * Glob repository filter.
 *<p>Uses path part of request for matching.
 *<p>Yaml format:
 * <pre>
 *   filter: expression
 *   priority: priority_value
 *
 *   where
 *     'filter' is mandatory and value contains globbing expression for request path matching.
 *     'priority_value' is optional and provides priority value. Default value is zero priority.
 * </pre>
 */
public final class GlobFilter extends Filter {

    private final PathMatcher matcher;

    /**
     * @param yaml Yaml mapping to read filters from
     */
    public GlobFilter(final YamlMapping yaml) {
        super(yaml);
        this.matcher = FileSystems.getDefault().getPathMatcher(
            String.format("glob:%s", yaml.string("filter"))
        );
    }

    @Override
    public boolean check(RequestLine line, Headers headers) {
        return this.matcher.matches(Paths.get(line.uri().getPath()));
    }
}
