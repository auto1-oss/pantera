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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;

import java.net.URL;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * Main HTTP slice NPM Proxy adapter.
 */
public final class NpmProxySlice implements Slice {
    /**
     * Route.
     */
    private final SliceRoute route;

    /**
     * @param path NPM proxy repo path ("" if NPM proxy should handle ROOT context path),
     *  or, in other words, repository name
     * @param npm NPM Proxy facade
     * @param packages Queue with uploaded from remote packages
     * @param repoName Repository name
     * @param repoType Repository type
     * @param cooldown Cooldown service
     * @param cooldownMetadata Cooldown metadata filtering service
     * @param remote Remote slice for security audit endpoints
     */
    public NpmProxySlice(
        final String path, final NpmProxy npm, final Optional<Queue<ProxyArtifactEvent>> packages,
        final String repoName, final String repoType, final CooldownService cooldown,
        final CooldownMetadataService cooldownMetadata, final com.auto1.pantera.http.Slice remote
    ) {
        this(path, npm, packages, repoName, repoType, cooldown, cooldownMetadata, remote, Optional.empty());
    }

    /**
     * @param path NPM proxy repo path ("" if NPM proxy should handle ROOT context path),
     *  or, in other words, repository name
     * @param npm NPM Proxy facade
     * @param packages Queue with uploaded from remote packages
     * @param repoName Repository name
     * @param repoType Repository type
     * @param cooldown Cooldown service
     * @param cooldownMetadata Cooldown metadata filtering service
     * @param remote Remote slice for security audit endpoints
     * @param baseUrl Base URL for the repository (from configuration)
     */
    public NpmProxySlice(
        final String path, final NpmProxy npm, final Optional<Queue<ProxyArtifactEvent>> packages,
        final String repoName, final String repoType, final CooldownService cooldown,
        final CooldownMetadataService cooldownMetadata, final com.auto1.pantera.http.Slice remote,
        final Optional<URL> baseUrl
    ) {
        final PackagePath ppath = new PackagePath(path);
        final AssetPath apath = new AssetPath(path);
        final CooldownInspector inspector =
            new RegistryBackedInspector("npm", PublishDateRegistries.instance());
        this.route = new SliceRoute(
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(ppath.pattern())
                ),
                new LoggingSlice(
                    new DownloadPackageSlice(npm, ppath, baseUrl, cooldownMetadata, repoType, repoName)
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(apath.pattern())
                ),
                new LoggingSlice(
                    new DownloadAssetSlice(npm, apath, packages, repoName, repoType, cooldown, inspector)
                )
            ),
            // Pass-through for npm security audit endpoints to upstream registry
            new RtRulePath(
                new RtRule.All(
                    MethodRule.POST,
                    new RtRule.ByPath(auditPattern(path))
                ),
                new LoggingSlice(
                    new SecurityAuditProxySlice(remote, path)
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.POST,
                    new RtRule.ByPath(auditPatternNoDash(path))
                ),
                new LoggingSlice(
                    new SecurityAuditProxySlice(remote, path)
                )
            ),
            new RtRulePath(
                RtRule.FALLBACK,
                new LoggingSlice(
                    new SliceSimple(
                        ResponseBuilder.notFound().jsonBody("{\"error\" : \"not found\"}").build()
                    )
                )
            )
        );
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line,
                                                    final Headers headers,
                                                    final Content body) {
        return this.route.response(line, headers, body);
    }

    private static String auditPattern(final String prefix) {
        final String base = (prefix == null || prefix.isEmpty())
            ? ""
            : String.format("/%s", java.util.regex.Pattern.quote(prefix));
        // Matches: audits (legacy) and audits/quick, and advisories/bulk
        final String prefixPath = base.isEmpty() ? "" : base;
        return String.format(
            "^(?:%1$s/-/npm/v1/security/audits(?:/quick)?|%1$s/-/npm/v1/security/advisories/bulk)$",
            prefixPath
        );
    }

    private static String auditPatternNoDash(final String prefix) {
        final String base = (prefix == null || prefix.isEmpty())
            ? ""
            : String.format("/%s", java.util.regex.Pattern.quote(prefix));
        // Some clients may call without leading -/, handle audits and advisories/bulk as well
        final String prefixPath = base.isEmpty() ? "" : base;
        return String.format(
            "^(?:%1$s/npm/v1/security/audits(?:/quick)?|%1$s/npm/v1/security/advisories/bulk)$",
            prefixPath
        );
    }
}
