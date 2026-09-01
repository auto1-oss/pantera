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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.CombinedAuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticating decorator for the global maintenance routes ({@code
 * /.import/<repo>/...} and {@code /.merge/<repo>}) whose target repository
 * is named in the request path rather than fixed at wiring time.
 *
 * <p>Each request's repository segment is extracted from the path, and the
 * request is authorized against {@link CombinedAuthzSlice} with a repository-
 * scoped {@link AdapterBasicPermission} for the configured {@link Action}
 * (typically {@code WRITE}). An anonymous or otherwise unauthenticated
 * request is rejected with {@code 401}; an authenticated principal lacking
 * the repository-scoped permission gets {@code 403}. A path from which no
 * repository can be resolved <b>fails closed</b> with {@code 404} — the sink
 * never runs.</p>
 *
 * <p>Before 2.2.9 these routes were mounted in {@code MainSlice} ahead of the
 * authentication chain, so they were reachable with no credential at all —
 * an unauthenticated artifact overwrite / metadata-merge primitive, and the
 * entry point for the JRuby gem-path RCE. This slice restores the same
 * repository-scoped authorization every adapter upload path already enforces
 * (mirrors {@code CombinedAuthzSliceWrap} usage in {@code RepositorySlices}).</p>
 *
 * @since 2.2.9
 */
public final class RepoScopedAuthSlice implements Slice {

    /**
     * Protected origin (the importer / merge sink).
     */
    private final Slice origin;

    /**
     * Basic authentication scheme.
     */
    private final Authentication basicAuth;

    /**
     * Token authentication scheme.
     */
    private final TokenAuthentication tokenAuth;

    /**
     * Security policy resolving a principal's permissions.
     */
    private final Policy<?> policy;

    /**
     * Pattern whose first group captures the (still URL-encoded) repository
     * name from the request path.
     */
    private final Pattern repoInPath;

    /**
     * Repository-scoped action required to reach the origin.
     */
    private final Action action;

    /**
     * Ctor.
     *
     * @param origin Protected origin slice
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param policy Security policy
     * @param repoInPath Path pattern capturing the repository name in group 1
     * @param action Repository-scoped action required
     * @checkstyle ParameterNumberCheck (12 lines)
     */
    public RepoScopedAuthSlice(
        final Slice origin,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final Policy<?> policy,
        final Pattern repoInPath,
        final Action action
    ) {
        this.origin = origin;
        this.basicAuth = basicAuth;
        this.tokenAuth = tokenAuth;
        this.policy = policy;
        this.repoInPath = repoInPath;
        this.action = action;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final Matcher matcher = this.repoInPath.matcher(line.uri().getPath());
        final CompletableFuture<Response> result;
        if (matcher.find() && !matcher.group(1).isEmpty()) {
            final String repo = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
            result = new CombinedAuthzSlice(
                this.origin,
                this.basicAuth,
                this.tokenAuth,
                new OperationControl(
                    this.policy, new AdapterBasicPermission(repo, this.action)
                )
            ).response(line, headers, body);
        } else {
            // No resolvable repository — fail closed, and drain the body so
            // the reactive publisher is not leaked.
            result = body.asBytesFuture().thenApply(
                ignored -> ResponseBuilder.notFound()
                    .textBody("Cannot determine target repository from path")
                    .build()
            );
        }
        return result;
    }
}
