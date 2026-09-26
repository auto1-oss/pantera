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
package com.auto1.pantera.http.client.egress;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Process-wide source of the outbound egress settings for consumers that
 * live below the settings database: {@link EgressFilteringResolver} (every
 * outbound connect) and {@link com.auto1.pantera.http.client.auth.RealmTrust}
 * (every credential-release decision).
 *
 * <p>{@code pantera-main}'s {@code EgressSettingsLoader} installs suppliers
 * backed by the {@code auth_settings} table (DB, then environment, then
 * defaults) at boot; the suppliers returned here are <em>live</em>, so a
 * consumer may capture one at construction and still observe an admin
 * edit on its next decision. Without an installed supplier -- unit tests,
 * a DB-less boot -- the environment-derived defaults apply. This mirrors
 * {@code ClientBaseUrlSettingsRegistry} in pantera-core: http-client must
 * not depend on the DAO, so the dependency is inverted through this
 * holder.</p>
 *
 * @since 2.2.9
 */
public final class EgressSettingsRegistry {

    /**
     * Environment variable naming hosts a bearer realm may live on.
     */
    public static final String ENV_CREDENTIAL_ALLOW_HOSTS =
        "PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS";

    /**
     * Installed policy supplier, or null.
     */
    private static volatile Supplier<EgressPolicy> policy;

    /**
     * Installed credential-host supplier, or null.
     */
    private static volatile Supplier<Set<String>> credentialHosts;

    /**
     * Static holder.
     */
    private EgressSettingsRegistry() {
    }

    /**
     * Install the DB-backed suppliers.
     *
     * @param policySupplier Outbound address policy
     * @param credentialHostsSupplier Hosts trusted to receive upstream credentials
     */
    public static void install(
        final Supplier<EgressPolicy> policySupplier,
        final Supplier<Set<String>> credentialHostsSupplier
    ) {
        EgressSettingsRegistry.policy = policySupplier;
        EgressSettingsRegistry.credentialHosts = credentialHostsSupplier;
    }

    /**
     * Drop the installed suppliers (tests, shutdown).
     */
    public static void uninstall() {
        EgressSettingsRegistry.policy = null;
        EgressSettingsRegistry.credentialHosts = null;
    }

    /**
     * Live supplier of the outbound address policy.
     *
     * @return Supplier that follows install/uninstall
     */
    public static Supplier<EgressPolicy> policy() {
        return () -> {
            final Supplier<EgressPolicy> current = EgressSettingsRegistry.policy;
            return current == null ? EnvironmentDefaults.POLICY : current.get();
        };
    }

    /**
     * Live supplier of the hosts trusted to receive upstream credentials.
     *
     * @return Supplier that follows install/uninstall
     */
    public static Supplier<Set<String>> credentialAllowHosts() {
        return () -> {
            final Supplier<Set<String>> current = EgressSettingsRegistry.credentialHosts;
            return current == null ? EnvironmentDefaults.CREDENTIAL_HOSTS : current.get();
        };
    }

    /**
     * Parse a comma-separated host list into a lower-cased set.
     *
     * @param spec Comma-separated hosts, may be empty
     * @return Normalised hosts
     */
    public static Set<String> parseHosts(final String spec) {
        final Set<String> lower = new HashSet<>();
        for (final String host : Arrays.asList(spec.split(","))) {
            if (host != null && !host.isBlank()) {
                lower.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(lower);
    }

    /**
     * Environment tier, read once on first use (class-init lazy).
     */
    private static final class EnvironmentDefaults {

        /**
         * Policy from {@code PANTERA_EGRESS_*}.
         */
        static final EgressPolicy POLICY = EgressPolicy.fromEnvironment();

        /**
         * Hosts from {@value EgressSettingsRegistry#ENV_CREDENTIAL_ALLOW_HOSTS}.
         */
        static final Set<String> CREDENTIAL_HOSTS = EgressSettingsRegistry.parseHosts(
            System.getenv().getOrDefault(ENV_CREDENTIAL_ALLOW_HOSTS, "")
        );

        /**
         * Static holder.
         */
        private EnvironmentDefaults() {
        }
    }
}
