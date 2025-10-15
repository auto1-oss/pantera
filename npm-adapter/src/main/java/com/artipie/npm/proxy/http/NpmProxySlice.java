/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.http;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rt.MethodRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.LoggingSlice;
import com.artipie.http.slice.SliceSimple;
import com.artipie.npm.proxy.NpmProxy;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.cooldown.CooldownService;

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
     * @param remote Remote slice for security audit endpoints
     */
    public NpmProxySlice(
        final String path, final NpmProxy npm, final Optional<Queue<ProxyArtifactEvent>> packages,
        final String repoName, final String repoType, final CooldownService cooldown,
        final com.artipie.http.Slice remote
    ) {
        this(path, npm, packages, repoName, repoType, cooldown, remote, Optional.empty());
    }

    /**
     * @param path NPM proxy repo path ("" if NPM proxy should handle ROOT context path),
     *  or, in other words, repository name
     * @param npm NPM Proxy facade
     * @param packages Queue with uploaded from remote packages
     * @param repoName Repository name
     * @param repoType Repository type
     * @param cooldown Cooldown service
     * @param remote Remote slice for security audit endpoints
     * @param baseUrl Base URL for the repository (from configuration)
     */
    public NpmProxySlice(
        final String path, final NpmProxy npm, final Optional<Queue<ProxyArtifactEvent>> packages,
        final String repoName, final String repoType, final CooldownService cooldown,
        final com.artipie.http.Slice remote, final Optional<URL> baseUrl
    ) {
        final PackagePath ppath = new PackagePath(path);
        final AssetPath apath = new AssetPath(path);
        final NpmCooldownInspector inspector = new NpmCooldownInspector(npm.remoteClient());
        this.route = new SliceRoute(
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(ppath.pattern())
                ),
                new LoggingSlice(
                    new DownloadPackageSlice(npm, ppath, baseUrl)
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
