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
package com.auto1.pantera.http;

/**
 * Default {@code User-Agent} for outbound traffic from Pantera to any upstream
 * registry, proxy, or HEAD-probe target.
 *
 * <p>A few public registries (Maven Central, registry.npmjs.org, PyPI) classify
 * unidentified or {@code Java/<version>}-style headers as bot traffic and
 * apply aggressive rate-limits, intermittent {@code 403 Forbidden}, or hard
 * blocks. Setting an explicit {@code User-Agent} that identifies the software
 * (and links to its homepage so registry operators can contact us) avoids
 * those defenses.
 *
 * <p>Format follows the de-facto convention used by package managers and
 * proxies: <code>Pantera/{version} (+{url}) {component}</code>. The trailing
 * <code>{component}</code> hint is optional — supply it when the same JVM
 * issues different classes of upstream requests (e.g. cooldown publish-date
 * lookups vs. repository proxy traffic) so registry-side log analysis can
 * tell them apart.
 *
 * @since 2.2.0
 */
public final class PanteraUserAgent {

    private static final String HOMEPAGE = "https://github.com/auto1-oss/pantera";

    private static final String VERSION = resolveVersion();

    private static final String BASE = "Pantera/" + VERSION + " (+" + HOMEPAGE + ")";

    private PanteraUserAgent() {
    }

    /**
     * Default {@code User-Agent} suitable for any outbound request.
     *
     * @return e.g. {@code "Pantera/2.2.0 (+https://github.com/auto1-oss/pantera)"}
     */
    public static String defaultUserAgent() {
        return BASE;
    }

    /**
     * {@code User-Agent} with a component qualifier appended, useful when one
     * service has multiple distinct outbound workloads.
     *
     * @param component short identifier (e.g. {@code "publish-date"}, {@code "proxy"})
     * @return e.g. {@code "Pantera/2.2.0 (+...) publish-date"}
     */
    public static String userAgentWithComponent(final String component) {
        if (component == null || component.isEmpty()) {
            return BASE;
        }
        return BASE + ' ' + component;
    }

    private static String resolveVersion() {
        final Package pkg = PanteraUserAgent.class.getPackage();
        if (pkg != null) {
            final String impl = pkg.getImplementationVersion();
            if (impl != null && !impl.isBlank()) {
                return impl;
            }
        }
        // Dev / non-jarred run — manifest absent.
        return "dev";
    }
}
