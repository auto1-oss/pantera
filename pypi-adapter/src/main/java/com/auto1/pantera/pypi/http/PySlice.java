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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.http.slice.SliceWithHeaders;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.RepositoryEvents;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * PyPi HTTP entry point.
 */
public final class PySlice extends Slice.Wrap {

    /**
     * Repository type name.
     */
    private static final String REPO_TYPE = "pypi";

    /**
     * Primary ctor.
     * @param storage The storage.
     * @param policy Access policy.
     * @param auth Concrete identities.
     * @param name Repository name
     * @param queue Events queue
     */
    public PySlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication auth,
        final String name,
        final Optional<Queue<ArtifactEvent>> queue
    ) {
        this(storage, policy, auth, null, name, queue,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with combined authentication support.
     * @param storage The storage.
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param queue Events queue
     */
    public PySlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> queue
    ) {
        this(storage, policy, basicAuth, tokenAuth, name, queue,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous artifact-index writer.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public PySlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> queue,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath(".*\\.(whl|tar\\.gz|zip|tar\\.bz2|tar\\.Z|tar|egg)")
                    ),
                    PySlice.createAuthSlice(
                        new SliceWithHeaders(
                            new StorageArtifactSlice(storage),
                            Headers.from(ContentType.mime("application/octet-stream"))
                        ),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.POST,
                        new RtRule.ByHeader(
                            "content-type", Pattern.compile("multipart.*", Pattern.CASE_INSENSITIVE)
                        )
                    ),
                    PySlice.createAuthSlice(
                        new WheelSlice(storage, queue, name, syncIndex),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.POST,
                        new RtRule.ByHeader(
                            "content-type", Pattern.compile("text.*", Pattern.CASE_INSENSITIVE)
                        )
                    ),
                    PySlice.createAuthSlice(
                        new SearchSlice(storage),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath("(^\\/)|(.*(\\/[a-z0-9\\-]+?\\/?$))")
                    ),
                    new BasicAuthzSlice(
                        new SliceIndex(storage),
                        basicAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET
                    ),
                    PySlice.createAuthSlice(
                        new RedirectSlice(),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    MethodRule.DELETE,
                    PySlice.createAuthSlice(
                        new DeleteSlice(
                            storage,
                            queue.map(
                                item -> new RepositoryEvents(PySlice.REPO_TYPE, name, item)
                            )
                        ),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                                policy,
                                new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                ),
                // HEAD support for hosted artifacts + index pages. uv (and other
                // resolvers) probe `.whl` URLs with HEAD before deciding to
                // stream — without these routes the request fell through to
                // the FALLBACK and returned 404 even though the file existed.
                // Both HEAD routes delegate to the same handlers as their GET
                // counterparts via {@link HeadAsGetSlice}, which drains the
                // body and returns the response headers.
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.HEAD,
                        new RtRule.ByPath(
                            ".*\\.(whl|tar\\.gz|zip|tar\\.bz2|tar\\.Z|tar|egg)"
                        )
                    ),
                    PySlice.createAuthSlice(
                        new HeadAsGetSlice(
                            new SliceWithHeaders(
                                new StorageArtifactSlice(storage),
                                Headers.from(ContentType.mime("application/octet-stream"))
                            )
                        ),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.HEAD,
                        new RtRule.ByPath("(^\\/)|(.*(\\/[a-z0-9\\-]+?\\/?$))")
                    ),
                    new BasicAuthzSlice(
                        new HeadAsGetSlice(new SliceIndex(storage)),
                        basicAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK,
                    new SliceSimple(ResponseBuilder.notFound().build())
                )
            )
        );
    }

    /**
     * Adapter that turns a HEAD request into a GET against the wrapped
     * slice, then drops the body before returning. RFC 9110 §9.3.2 lets
     * a server respond to HEAD by computing the GET response and omitting
     * the body — this is the minimum-effort implementation: the underlying
     * GET path stays the single source of truth for "does this file exist
     * and what are its headers".
     *
     * <p>The drained body is discarded; status and headers are forwarded
     * to the caller. The Content-Length header (if present in the GET
     * response) lets HEAD callers size-check without downloading.</p>
     */
    private static final class HeadAsGetSlice implements Slice {

        private final Slice origin;

        HeadAsGetSlice(final Slice origin) {
            this.origin = origin;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final RequestLine asGet = new RequestLine(
                RqMethod.GET, line.uri(), line.version()
            );
            return this.origin.response(asGet, headers, body).thenCompose(resp ->
                resp.body().asBytesFuture().thenApply(ignored ->
                    new Response(resp.status(), resp.headers(), Content.EMPTY)
                )
            );
        }
    }

    /**
     * Creates appropriate auth slice based on available authentication methods.
     * @param origin Original slice to wrap
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param control Operation control
     * @return Auth slice
     */
    private static Slice createAuthSlice(
        final Slice origin, final Authentication basicAuth, 
        final TokenAuthentication tokenAuth, final OperationControl control
    ) {
        if (tokenAuth != null) {
            return new CombinedAuthzSliceWrap(origin, basicAuth, tokenAuth, control);
        }
        return new BasicAuthzSlice(origin, basicAuth, control);
    }
}
