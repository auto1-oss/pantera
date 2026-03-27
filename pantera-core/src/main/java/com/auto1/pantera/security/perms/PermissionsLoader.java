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
package com.auto1.pantera.security.perms;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.factory.FactoryLoader;
import java.security.PermissionCollection;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Load from the packages via reflection and instantiate permission factories object.
 * @since 1.2
 */
public final class PermissionsLoader extends
    FactoryLoader<PermissionFactory<PermissionCollection>, PanteraPermissionFactory,
        PermissionConfig, PermissionCollection> {

    /**
     * Environment parameter to define packages to find permission factories.
     * Package names should be separated with semicolon ';'.
     */
    public static final String SCAN_PACK = "PERM_FACTORY_SCAN_PACKAGES";

    /**
     * Ctor to obtain factories according to env.
     */
    public PermissionsLoader() {
        this(System.getenv());
    }

    /**
     * Ctor.
     * @param env Environment
     */
    public PermissionsLoader(final Map<String, String> env) {
        super(PanteraPermissionFactory.class, env);
    }

    @Override
    public Set<String> defPackages() {
        return Stream.of("com.auto1.pantera.security", "com.auto1.pantera.docker", "com.auto1.pantera.api.perms")
            .collect(Collectors.toSet());
    }

    @Override
    public String scanPackagesEnv() {
        return PermissionsLoader.SCAN_PACK;
    }

    @Override
    public PermissionCollection newObject(final String type, final PermissionConfig config) {
        final PermissionFactory<?> factory = this.factories.get(type);
        if (factory == null) {
            throw new PanteraException(String.format("Permission type %s is not found", type));
        }
        return factory.newPermissions(config);
    }

    @Override
    public String getFactoryName(final Class<?> clazz) {
        return Arrays.stream(clazz.getAnnotations())
            .filter(PanteraPermissionFactory.class::isInstance)
            .map(inst -> ((PanteraPermissionFactory) inst).value())
            .findFirst()
            .orElseThrow(
                () -> new PanteraException("Annotation 'PanteraPermissionFactory' should have a not empty value")
            );
    }
}
