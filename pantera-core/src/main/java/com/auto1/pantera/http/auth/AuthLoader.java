/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.auth;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.factory.FactoryLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Authentication instances loader.
 * @since 1.3
 */
public final class AuthLoader extends
    FactoryLoader<AuthFactory, PanteraAuthFactory, YamlMapping, Authentication> {

    /**
     * Environment parameter to define packages to find auth factories.
     * Package names should be separated with semicolon ';'.
     */
    public static final String SCAN_PACK = "AUTH_FACTORY_SCAN_PACKAGES";

    /**
     * Ctor.
     * @param env Environment variable map
     */
    public AuthLoader(final Map<String, String> env) {
        super(PanteraAuthFactory.class, env);
    }

    /**
     * Ctor.
     */
    public AuthLoader() {
        this(System.getenv());
    }

    @Override
    public Set<String> defPackages() {
        return Collections.singleton("com.auto1.pantera");
    }

    @Override
    public String scanPackagesEnv() {
        return AuthLoader.SCAN_PACK;
    }

    @Override
    public Authentication newObject(final String type, final YamlMapping mapping) {
        final AuthFactory factory = this.factories.get(type);
        if (factory == null) {
            throw new PanteraException(String.format("Auth type %s is not found", type));
        }
        return factory.getAuthentication(mapping);
    }

    @Override
    public String getFactoryName(final Class<?> clazz) {
        return Arrays.stream(clazz.getAnnotations())
            .filter(PanteraAuthFactory.class::isInstance)
            .map(inst -> ((PanteraAuthFactory) inst).value())
            .findFirst()
            .orElseThrow(
                () -> new PanteraException("Annotation 'PanteraAuthFactory' should have a not empty value")
            );
    }
}
