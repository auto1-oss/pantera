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
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.factory.FactoryLoader;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Load annotated by {@link PanteraFilterFactory} annotation {@link FilterFactory} classes
 * from the packages via reflection and instantiate filters.
 * @since 1.2
 */
public final class FilterFactoryLoader extends
    FactoryLoader<FilterFactory, PanteraFilterFactory,
    YamlMapping, Filter> {

    /**
     * Environment parameter to define packages to find filter factories.
     * Package names should be separated with semicolon ';'.
     */
    public static final String SCAN_PACK = "FILTER_FACTORY_SCAN_PACKAGES";

    /**
     * Ctor to obtain factories according to env.
     */
    public FilterFactoryLoader() {
        this(System.getenv());
    }

    /**
     * Ctor.
     * @param env Environment
     */
    public FilterFactoryLoader(final Map<String, String> env) {
        super(PanteraFilterFactory.class, env);
    }

    @Override
    public Set<String> defPackages() {
        return Stream.of("com.auto1.pantera.http.filter").collect(Collectors.toSet());
    }

    @Override
    public String scanPackagesEnv() {
        return FilterFactoryLoader.SCAN_PACK;
    }

    @Override
    public Filter newObject(final String type, final YamlMapping yaml) {
        final FilterFactory factory = this.factories.get(type);
        if (factory == null) {
            throw new PanteraException(
                String.format(
                    "%s type %s is not found",
                    Filter.class.getSimpleName(),
                    type
                )
            );
        }
        return factory.newFilter(yaml);
    }

    @Override
    public String getFactoryName(final Class<?> clazz) {
        return Arrays.stream(clazz.getAnnotations())
            .filter(PanteraFilterFactory.class::isInstance)
            .map(inst -> ((PanteraFilterFactory) inst).value())
            .findFirst()
            .orElseThrow(
                () -> new PanteraException(
                    String.format(
                        "Annotation '%s' should have a not empty value",
                        PanteraFilterFactory.class.getSimpleName()
                    )
                )
            );
    }
}
