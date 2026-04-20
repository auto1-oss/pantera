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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.composer.cooldown.ComposerCooldownResponseFactory;
import com.auto1.pantera.composer.cooldown.ComposerMetadataFilter;
import com.auto1.pantera.composer.cooldown.ComposerMetadataParser;
import com.auto1.pantera.composer.cooldown.ComposerMetadataRequestDetector;
import com.auto1.pantera.composer.cooldown.ComposerMetadataRewriter;
import com.auto1.pantera.cooldown.config.CooldownAdapterBundle;
import com.auto1.pantera.cooldown.config.CooldownAdapterRegistry;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.docker.cooldown.DockerCooldownResponseFactory;
import com.auto1.pantera.docker.cooldown.DockerMetadataFilter;
import com.auto1.pantera.docker.cooldown.DockerMetadataParser;
import com.auto1.pantera.docker.cooldown.DockerMetadataRequestDetector;
import com.auto1.pantera.docker.cooldown.DockerMetadataRewriter;
import com.auto1.pantera.http.cooldown.GoCooldownResponseFactory;
import com.auto1.pantera.http.cooldown.GoMetadataFilter;
import com.auto1.pantera.http.cooldown.GoMetadataParser;
import com.auto1.pantera.http.cooldown.GoMetadataRequestDetector;
import com.auto1.pantera.http.cooldown.GoMetadataRewriter;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import com.auto1.pantera.maven.cooldown.MavenMetadataFilter;
import com.auto1.pantera.maven.cooldown.MavenMetadataParser;
import com.auto1.pantera.maven.cooldown.MavenMetadataRequestDetector;
import com.auto1.pantera.maven.cooldown.MavenMetadataRewriter;
import com.auto1.pantera.npm.cooldown.NpmCooldownResponseFactory;
import com.auto1.pantera.npm.cooldown.NpmMetadataFilter;
import com.auto1.pantera.npm.cooldown.NpmMetadataParser;
import com.auto1.pantera.npm.cooldown.NpmMetadataRequestDetector;
import com.auto1.pantera.npm.cooldown.NpmMetadataRewriter;
import com.auto1.pantera.pypi.cooldown.PypiCooldownResponseFactory;
import com.auto1.pantera.pypi.cooldown.PypiMetadataFilter;
import com.auto1.pantera.pypi.cooldown.PypiMetadataParser;
import com.auto1.pantera.pypi.cooldown.PypiMetadataRequestDetector;
import com.auto1.pantera.pypi.cooldown.PypiMetadataRewriter;

/**
 * Registers all per-adapter cooldown component bundles at startup.
 *
 * <p>Called once from {@link CooldownSupport#wireAdapters()} during
 * application initialization. Each bundle groups the parser, filter,
 * rewriter, metadata-request detector, and 403-response factory for
 * a single repository type. The proxy layer ({@code BaseCachedProxySlice})
 * looks up the bundle by repo type at request time.</p>
 *
 * <p>Adapter mapping:</p>
 * <ul>
 *   <li>maven, gradle-proxy, maven-proxy &rarr; Maven bundle</li>
 *   <li>npm, npm-proxy &rarr; npm bundle (no detector &mdash; npm uses its own path)</li>
 *   <li>pypi, pypi-proxy &rarr; PyPI bundle</li>
 *   <li>docker, docker-proxy &rarr; Docker bundle</li>
 *   <li>go, go-proxy &rarr; Go bundle</li>
 *   <li>php, php-proxy &rarr; Composer bundle</li>
 *   <li>gradle &rarr; reuses Maven bundle</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class CooldownWiring {

    private CooldownWiring() {
    }

    /**
     * Register all adapter bundles into the global registries.
     * Both {@link CooldownAdapterRegistry} (full bundles for metadata routing)
     * and {@link CooldownResponseRegistry} (403 factories for direct-artifact blocks)
     * are populated.
     */
    @SuppressWarnings("PMD.ExcessiveMethodLength")
    public static void registerAllAdapters() {
        final CooldownAdapterRegistry adapters = CooldownAdapterRegistry.instance();
        final CooldownResponseRegistry responses = CooldownResponseRegistry.instance();

        // --- Maven (+ Gradle alias) ---
        final var mavenBundle = new CooldownAdapterBundle<>(
            new MavenMetadataParser(),
            new MavenMetadataFilter(),
            new MavenMetadataRewriter(),
            new MavenMetadataRequestDetector(),
            new MavenCooldownResponseFactory()
        );
        adapters.register("maven", mavenBundle);
        adapters.register("maven-proxy", mavenBundle);
        adapters.register("gradle", mavenBundle);
        adapters.register("gradle-proxy", mavenBundle);
        responses.register(new MavenCooldownResponseFactory(), "gradle", "gradle-proxy", "maven-proxy");

        // --- npm ---
        // npm has its own metadata filtering path in DownloadPackageSlice,
        // but we still register the bundle so BaseCachedProxySlice can use it
        // for any future unification, and so the 403 factory is available.
        final var npmBundle = new CooldownAdapterBundle<>(
            new NpmMetadataParser(),
            new NpmMetadataFilter(),
            new NpmMetadataRewriter(),
            new NpmMetadataRequestDetector(),
            new NpmCooldownResponseFactory()
        );
        adapters.register("npm", npmBundle);
        adapters.register("npm-proxy", npmBundle);
        responses.register(new NpmCooldownResponseFactory(), "npm-proxy");

        // --- PyPI ---
        // Both PyPI metadata surfaces — {@code /simple/<pkg>/} (PEP 503
        // HTML / PEP 691 JSON) and {@code /pypi/<pkg>/json} (JSON API) —
        // are consumed via handler dispatch inside the PyPI {@code
        // ProxySlice}, not via {@link
        // com.auto1.pantera.cooldown.metadata.MetadataFilterService}.
        // See {@code PypiSimpleHandler} and {@code PypiJsonHandler} for
        // the actual orchestration; the bundle registered here is kept
        // so the 403 response factory is discoverable for direct-artifact
        // block paths. This mirrors the Go adapter's split between
        // {@code /@v/list} (handler) and {@code /@latest} (handler) —
        // both bypass the SPI service for the same reason (per-adapter
        // flows need sibling-endpoint fallback logic the generic service
        // does not model).
        final var pypiBundle = new CooldownAdapterBundle<>(
            new PypiMetadataParser(),
            new PypiMetadataFilter(),
            new PypiMetadataRewriter(),
            new PypiMetadataRequestDetector(),
            new PypiCooldownResponseFactory()
        );
        adapters.register("pypi", pypiBundle);
        adapters.register("pypi-proxy", pypiBundle);
        responses.register(new PypiCooldownResponseFactory(), "pypi-proxy");

        // --- Docker ---
        final var dockerBundle = new CooldownAdapterBundle<>(
            new DockerMetadataParser(),
            new DockerMetadataFilter(),
            new DockerMetadataRewriter(),
            new DockerMetadataRequestDetector(),
            new DockerCooldownResponseFactory()
        );
        adapters.register("docker", dockerBundle);
        adapters.register("docker-proxy", dockerBundle);
        responses.register(new DockerCooldownResponseFactory(), "docker-proxy");

        // --- Go ---
        // The {@code /@v/list} bundle is registered here for consistency with
        // other adapters and for the 403 response factory. The Go adapter also
        // ships a second set of SPI implementations for the {@code /@latest}
        // endpoint — {@code GoLatestMetadataRequestDetector},
        // {@code GoLatestMetadataParser}, {@code GoLatestMetadataFilter} and
        // {@code GoLatestMetadataRewriter} — wired directly inside
        // {@code CachedProxySlice} (go-adapter) via {@code GoLatestHandler}.
        // The {@code /@latest} handler cannot share a single-detector bundle
        // slot because its flow needs a sibling {@code /@v/list} fetch to
        // resolve a fallback, which the generic {@link
        // com.auto1.pantera.cooldown.metadata.MetadataFilterService} does not
        // model. Both code paths coexist: {@code /@v/list} requests go through
        // the bundle; {@code /@latest} requests go through the handler.
        final var goBundle = new CooldownAdapterBundle<>(
            new GoMetadataParser(),
            new GoMetadataFilter(),
            new GoMetadataRewriter(),
            new GoMetadataRequestDetector(),
            new GoCooldownResponseFactory()
        );
        adapters.register("go", goBundle);
        adapters.register("go-proxy", goBundle);
        responses.register(new GoCooldownResponseFactory(), "go-proxy");

        // --- Composer (PHP) ---
        final var composerBundle = new CooldownAdapterBundle<>(
            new ComposerMetadataParser(),
            new ComposerMetadataFilter(),
            new ComposerMetadataRewriter(),
            new ComposerMetadataRequestDetector(),
            new ComposerCooldownResponseFactory()
        );
        // ComposerCooldownResponseFactory.repoType() returns "composer" for its
        // own canonical key; register "php" and "php-proxy" as aliases so the
        // Composer proxy slices (which use repo type "php") resolve correctly.
        responses.register(new ComposerCooldownResponseFactory(), "php", "php-proxy");
        adapters.register("php", composerBundle);
        adapters.register("php-proxy", composerBundle);

        EcsLogger.info("com.auto1.pantera.cooldown")
            .message("Registered cooldown adapter bundles: " + adapters.registeredTypes())
            .eventCategory("configuration")
            .eventAction("adapter_wiring")
            .eventOutcome("success")
            .field("adapter.count", adapters.registeredTypes().size())
            .field("response_factory.count", responses.registeredTypes().size())
            .log();
    }
}
