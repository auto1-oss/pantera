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
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.conda.http.auth.TokenAuth;
import com.auto1.pantera.conda.http.auth.TokenAuthScheme;
import com.auto1.pantera.conda.http.auth.TokenAuthSlice;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.net.URI;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main conda entry point. Paths are relative to the repository: on the main
 * port Pantera trims the repository name, on a dedicated port the repository
 * is served at the root. anaconda performs various requests, for example:
 * /release/{username}/snappy/1.1.3
 * /dist/{username}/snappy/1.1.3/linux-64/snappy-1.1.3-0.tar.bz2
 * /t/{usertoken}/noarch/current_repodata.json
 * /t/{usertoken}/linux-64/snappy-1.1.3-0.tar.bz2
 * In the last two cases authentication is performed by the token in the path
 * (see {@link CondaUrlTokenSlice}). HEAD answers exactly what GET would,
 * without the body.
 * @since 0.4
 */
public final class CondaSlice extends Slice.Wrap {

    /**
     * Package key (the last two path segments) of a download request.
     */
    private static final Pattern PTRN =
        Pattern.compile(".*/([^/]+/[^/]+(\\.tar\\.bz2|\\.conda))$");

    /**
     * Ctor.
     * @param storage Storage
     * @param policy Permissions
     * @param users Users
     * @param tokens Tokens
     * @param url Application url
     * @param repo Repository name
     * @param events Events queue
     */
    public CondaSlice(final Storage storage, final Policy<?> policy, final Authentication users,
        final Tokens tokens, final String url, final String repo,
        final Optional<Queue<ArtifactEvent>> events) {
        this(storage, policy, users, tokens, url, repo, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous artifact-index writer.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public CondaSlice(final Storage storage, final Policy<?> policy, final Authentication users,
        final Tokens tokens, final String url, final String repo,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex) {
        this(storage, policy, users, tokens, url, repo, events, syncIndex, new UploadTickets());
    }

    /**
     * Ctor with synchronous artifact-index writer and upload tickets.
     * @param storage Storage
     * @param policy Permissions
     * @param users Users
     * @param tokens Tokens
     * @param url Application url
     * @param repo Repository name
     * @param events Events queue
     * @param syncIndex Synchronous artifact-index writer
     * @param tickets Upload tickets for the anaconda-client form upload
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public CondaSlice(final Storage storage, final Policy<?> policy, final Authentication users,
        final Tokens tokens, final String url, final String repo,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final UploadTickets tickets) {
        super(
            new HeadAsGetSlice(
                CondaSlice.routes(
                    new Setup(storage, policy, users, tokens, repo),
                    new PostStageCommitSlice(url, tickets, repo),
                    new UploadSlices(new UpdateSlice(storage, events, repo, syncIndex), tickets)
                )
            )
        );
    }

    /**
     * Route table for every method but HEAD (HEAD answers what GET would).
     * @param setup Repository setup
     * @param stage Stage/commit slice
     * @param upload Upload slices
     * @return Route slice
     */
    private static Slice routes(final Setup setup, final Slice stage, final UploadSlices upload) {
        final OperationControl read = setup.control(Action.Standard.READ);
        final OperationControl write = setup.control(Action.Standard.WRITE);
        final Tokens tokens = setup.tokens;
        return new SliceRoute(
            new RtRulePath(
                new RtRule.All(new RtRule.ByPath("/t/.*repodata\\.json$"), MethodRule.GET),
                new TokenAuthSlice(new DownloadRepodataSlice(setup.storage), read, tokens.auth())
            ),
            new RtRulePath(
                new RtRule.All(new RtRule.ByPath(".*repodata\\.json$"), MethodRule.GET),
                new BasicAuthzSlice(new DownloadRepodataSlice(setup.storage), setup.users, read)
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.ByPath(".*(/dist/|/t/).*(\\.tar\\.bz2|\\.conda)$"),
                    MethodRule.GET
                ),
                // The token (or dist) prefix is not part of the storage key:
                // serve the package stored under "<subdir>/<file>".
                new TokenAuthSlice(
                    new PackageKeySlice(new StorageArtifactSlice(setup.storage)),
                    read, tokens.auth()
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.ByPath(".*(\\.tar\\.bz2|\\.conda)$"), MethodRule.GET
                ),
                new BasicAuthzSlice(new StorageArtifactSlice(setup.storage), setup.users, read)
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.ByPath(".*/(stage|commit).*(\\.tar\\.bz2|\\.conda)$"),
                    MethodRule.POST
                ),
                new TokenAuthSlice(stage, read, tokens.auth())
            ),
            new RtRulePath(
                new RtRule.All(new RtRule.ByPath(".*/(package|release)/.*"), MethodRule.GET),
                new TokenAuthSlice(new GetPackageSlice(), read, tokens.auth())
            ),
            new RtRulePath(
                new RtRule.All(new RtRule.ByPath(".*/(package|release)/.*"), MethodRule.POST),
                new TokenAuthSlice(new PostPackageReleaseSlice(), write, tokens.auth())
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.ByPath(
                        "(/t/[^/]+)?/?[a-z0-9-._]*/[a-z0-9-._]*/[a-z0-9-._]*(\\.tar\\.bz2|\\.conda)$"
                    ),
                    MethodRule.POST
                ),
                // SECURITY (2.2.9): the package upload was the only route
                // without a credential-validating wrapper. Behind the
                // presence-only AnonymousAccessSlice gate, any bogus
                // `Authorization: token x` uploaded an attacker-crafted
                // package into a private channel (repodata merge + index
                // event). The upload needs a valid token with repository
                // WRITE, or an upload ticket the authenticated stage step
                // issued for exactly this package (anaconda-client's form
                // POST carries no credentials).
                new UploadAuthSlice(
                    upload.update,
                    new TokenAuthSlice(upload.update, write, tokens.auth()),
                    upload.tickets, setup.repo, write
                )
            ),
            new RtRulePath(
                new RtRule.All(new RtRule.ByPath(".*user$"), MethodRule.GET),
                new TokenAuthSlice(
                    new GetUserSlice(new TokenAuthScheme(new TokenAuth(tokens.auth()))),
                    read, tokens.auth()
                )
            ),
            new RtRulePath(
                new RtRule.All(new RtRule.ByPath(".*authentication-type$"), MethodRule.GET),
                new AuthTypeSlice()
            ),
            new RtRulePath(
                new RtRule.All(new RtRule.ByPath(".*authentications$"), MethodRule.POST),
                new BasicAuthzSlice(
                    new GenerateTokenSlice(setup.users, tokens), setup.users, write
                )
            ),
            new RtRulePath(
                new RtRule.All(new RtRule.ByPath(".*authentications$"), MethodRule.DELETE),
                new BasicAuthzSlice(new DeleteTokenSlice(tokens), setup.users, write)
            ),
            new RtRulePath(RtRule.FALLBACK, new SliceSimple(ResponseBuilder.notFound().build()))
        );
    }

    /**
     * Repository setup shared by the routes.
     * @since 2.2.9
     */
    private static final class Setup {

        /**
         * Storage.
         */
        private final Storage storage;

        /**
         * Policy.
         */
        private final Policy<?> policy;

        /**
         * Basic authentication.
         */
        private final Authentication users;

        /**
         * Tokens.
         */
        private final Tokens tokens;

        /**
         * Repository name.
         */
        private final String repo;

        /**
         * Ctor.
         * @param storage Storage
         * @param policy Policy
         * @param users Basic authentication
         * @param tokens Tokens
         * @param repo Repository name
         * @checkstyle ParameterNumberCheck (5 lines)
         */
        Setup(final Storage storage, final Policy<?> policy, final Authentication users,
            final Tokens tokens, final String repo) {
            this.storage = storage;
            this.policy = policy;
            this.users = users;
            this.tokens = tokens;
            this.repo = repo;
        }

        /**
         * Repository-scoped operation control.
         * @param action Action
         * @return Operation control
         */
        OperationControl control(final Action action) {
            return new OperationControl(
                this.policy, new AdapterBasicPermission(this.repo, action)
            );
        }
    }

    /**
     * Upload sink and its tickets.
     * @since 2.2.9
     */
    private static final class UploadSlices {

        /**
         * Upload sink.
         */
        private final Slice update;

        /**
         * Upload tickets.
         */
        private final UploadTickets tickets;

        /**
         * Ctor.
         * @param update Upload sink
         * @param tickets Upload tickets
         */
        UploadSlices(final Slice update, final UploadTickets tickets) {
            this.update = update;
            this.tickets = tickets;
        }
    }

    /**
     * Rewrites a download path to the package key: conda requests packages
     * as {@code /t/<token>/<subdir>/<file>} (or under {@code /dist/}), while
     * the package is stored under {@code <subdir>/<file>}.
     * @since 2.2.9
     */
    private static final class PackageKeySlice implements Slice {

        /**
         * Origin.
         */
        private final Slice origin;

        /**
         * Ctor.
         * @param origin Origin
         */
        PackageKeySlice(final Slice origin) {
            this.origin = origin;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final Matcher matcher = CondaSlice.PTRN.matcher(line.uri().getPath());
            final RequestLine effective;
            if (matcher.matches()) {
                effective = new RequestLine(
                    line.method(), URI.create(String.format("/%s", matcher.group(1))),
                    line.version()
                );
            } else {
                effective = line;
            }
            return this.origin.response(effective, headers, body);
        }
    }
}
