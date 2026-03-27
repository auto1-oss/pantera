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
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.security.policy.CachedDbPolicy;
import com.auto1.pantera.security.policy.PoliciesLoader;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.security.policy.YamlPolicyConfig;
import com.auto1.pantera.settings.cache.CachedUsers;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Pantera security: authentication and permissions policy.
 * @since 0.29
 */
public interface PanteraSecurity {

    /**
     * Instance of {@link CachedUsers} which implements
     * {@link Authentication} and {@link com.auto1.pantera.asto.misc.Cleanable}.
     * @return Cached users
     */
    Authentication authentication();

    /**
     * Permissions policy instance.
     * @return Policy
     */
    Policy<?> policy();

    /**
     * Policy storage if `pantera` policy is used or empty.
     * @return Storage for `pantera` policy
     */
    Optional<Storage> policyStorage();

    /**
     * Pantera security from yaml settings.
     * @since 0.29
     */
    class FromYaml implements PanteraSecurity {

        /**
         * YAML node name `type` for credentials type.
         */
        private static final String NODE_TYPE = "type";

        /**
         * Yaml node policy.
         */
        private static final String NODE_POLICY = "policy";

        /**
         * Permissions policy instance.
         */
        private final Policy<?> plc;

        /**
         * Instance of {@link CachedUsers} which implements
         * {@link Authentication} and {@link com.auto1.pantera.asto.misc.Cleanable}.
         */
        private final Authentication auth;

        /**
         * Policy storage if `pantera` policy is used or empty.
         */
        private final Optional<Storage> asto;

        /**
         * Ctor.
         * @param settings Yaml settings
         * @param auth Authentication instance
         * @param asto Policy storage
         */
        public FromYaml(final YamlMapping settings, final Authentication auth,
            final Optional<Storage> asto) {
            this(settings, auth, asto, null);
        }

        /**
         * Ctor with optional database source. When a DataSource is provided,
         * {@link CachedDbPolicy} is used instead of YAML-backed policy.
         * @param settings Yaml settings
         * @param auth Authentication instance
         * @param asto Policy storage
         * @param dataSource Database data source, nullable
         */
        public FromYaml(final YamlMapping settings, final Authentication auth,
            final Optional<Storage> asto, final DataSource dataSource) {
            this.auth = auth;
            this.plc = dataSource != null
                ? new CachedDbPolicy(dataSource)
                : FromYaml.initPolicy(settings);
            this.asto = asto;
        }

        @Override
        public Authentication authentication() {
            return this.auth;
        }

        @Override
        public Policy<?> policy() {
            return this.plc;
        }

        @Override
        public Optional<Storage> policyStorage() {
            return this.asto;
        }

        /**
         * Initialize policy. If policy section is absent, {@link Policy#FREE} is used.
         * @param settings Yaml settings
         * @return Policy instance
         */
        private static Policy<?> initPolicy(final YamlMapping settings) {
            final YamlMapping mapping = settings.yamlMapping(FromYaml.NODE_POLICY);
            final Policy<?> res;
            if (mapping == null) {
                res = Policy.FREE;
            } else {
                res = new PoliciesLoader().newObject(
                    mapping.string(FromYaml.NODE_TYPE), new YamlPolicyConfig(mapping)
                );
            }
            return res;
        }

    }

}
