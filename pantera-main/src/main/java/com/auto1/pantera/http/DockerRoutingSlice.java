/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthScheme;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.security.perms.EmptyPermissions;
import com.auto1.pantera.security.perms.FreePermissions;
import com.auto1.pantera.settings.Settings;
import org.apache.http.client.utils.URIBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice decorator which redirects all Docker V2 API requests to Pantera format paths.
 */
public final class DockerRoutingSlice implements Slice {

    /**
     * Real path header name.
     */
    private static final String HDR_REAL_PATH = "X-RealPath";

    /**
     * Docker V2 API path pattern.
     */
    private static final Pattern PTN_PATH = Pattern.compile("/v2((/.*)?)");

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Settings.
     */
    private final Settings settings;

    /**
     * Token authentication for the bare {@code /v2/} ping. Docker clients
     * validate {@code docker login} against this endpoint, so it must
     * accept the exact same credential shapes (Bearer and API-token-as-
     * Basic-password) as the repository-scoped Docker endpoints.
     */
    private final TokenAuthentication tokens;

    /**
     * Decorates slice with Docker V2 API routing.
     * @param settings Settings.
     * @param tokens Token authentication for the {@code /v2/} ping
     * @param origin Origin slice
     */
    DockerRoutingSlice(
        final Settings settings,
        final TokenAuthentication tokens,
        final Slice origin
    ) {
        this.settings = settings;
        this.tokens = tokens;
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        final String path = line.uri().getPath();
        final Matcher matcher = PTN_PATH.matcher(path);
        if (matcher.matches()) {
            final String group = matcher.group(1);
            if (group.isEmpty() || "/".equals(group)) {
                return new AuthzSlice(
                    (l, h, b) -> ResponseBuilder.ok()
                        .header("Docker-Distribution-API-Version", "registry/2.0")
                        .completedFuture(),
                    new CombinedAuthScheme(
                        this.settings.authz().authentication(), this.tokens
                    ),
                    new OperationControl(
                        user -> user.isAnonymous() ? EmptyPermissions.INSTANCE
                            : FreePermissions.INSTANCE,
                        new DockerRepositoryPermission("*", "*", DockerActions.PULL.mask())
                    )
                ).response(line, headers, body);
            } else {
                return this.origin.response(
                    new RequestLine(
                        line.method().toString(),
                        new URIBuilder(line.uri()).setPath(group).toString(),
                        line.version()
                    ),
                    headers.copy().add(DockerRoutingSlice.HDR_REAL_PATH, path),
                    body
                );
            }
        }
        return this.origin.response(line, headers, body);
    }

    /**
     * Slice which reverts real path from headers if exists.
     * @since 0.9
     */
    public static final class Reverted implements Slice {

        /**
         * Origin slice.
         */
        private final Slice origin;

        /**
         * New {@link Slice} decorator to revert real path.
         * @param origin Origin slice
         */
        public Reverted(final Slice origin) {
            this.origin = origin;
        }

        @Override
        public CompletableFuture<Response> response(final RequestLine line,
                                                    final Headers headers,
                                                    final Content body) {
            return this.origin.response(
                new RequestLine(
                    line.method().toString(),
                    new URIBuilder(line.uri())
                        .setPath(String.format("/v2%s", line.uri().getPath()))
                        .toString(),
                    line.version()
                ),
                headers,
                body
            );
        }
    }
}
