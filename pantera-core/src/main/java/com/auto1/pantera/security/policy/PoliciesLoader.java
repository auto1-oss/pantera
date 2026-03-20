/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.security.policy;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.FactoryLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Load via reflection and create existing instances of {@link PolicyFactory} implementations.
 * @since 1.2
 */
public final class PoliciesLoader extends
    FactoryLoader<PolicyFactory, PanteraPolicyFactory, Config, Policy<?>> {

    /**
     * Environment parameter to define packages to find policies factories.
     * Package names should be separated with semicolon ';'.
     */
    public static final String SCAN_PACK = "POLICY_FACTORY_SCAN_PACKAGES";

    /**
     * Ctor.
     * @param env Environment map
     */
    public PoliciesLoader(final Map<String, String> env) {
        super(PanteraPolicyFactory.class, env);
    }

    /**
     * Create policies from env.
     */
    public PoliciesLoader() {
        this(System.getenv());
    }

    @Override
    public Set<String> defPackages() {
        return Collections.singleton("com.auto1.pantera.security");
    }

    @Override
    public String scanPackagesEnv() {
        return PoliciesLoader.SCAN_PACK;
    }

    @Override
    public Policy<?> newObject(final String type, final Config config) {
        final PolicyFactory factory = this.factories.get(type);
        if (factory == null) {
            throw new PanteraException(String.format("Policy type %s is not found", type));
        }
        return factory.getPolicy(config);
    }

    @Override
    public String getFactoryName(final Class<?> clazz) {
        return Arrays.stream(clazz.getAnnotations())
            .filter(PanteraPolicyFactory.class::isInstance)
            .map(inst -> ((PanteraPolicyFactory) inst).value())
            .findFirst()
            .orElseThrow(
                () -> new PanteraException("Annotation 'PanteraPolicyFactory' should have a not empty value")
            );
    }
}
