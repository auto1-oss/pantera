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
import com.auto1.pantera.conda.http.auth.TokenAuthScheme;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authentication of the package upload route.
 *
 * <p>An upload authenticated with an upload ticket issued by the
 * {@code stage} step (see {@link UploadTickets}) is let through as the
 * ticket's user, provided that user still holds WRITE on the repository.
 * Anything else falls through to the regular token check.</p>
 *
 * @since 2.2.9
 */
final class UploadAuthSlice implements Slice {

    /**
     * Package key in the request path: the last two segments.
     */
    private static final Pattern PKG = Pattern.compile(".*/([^/]+/[^/]+(\\.tar\\.bz2|\\.conda))$");

    /**
     * Token in the request path.
     */
    private static final Pattern PATH_TOKEN = Pattern.compile("^/t/([^/]+)/.*");

    /**
     * Token in the {@code Authorization: token <t>} header.
     */
    private static final Pattern HDR_TOKEN = Pattern.compile(
        String.format("^%s\\s+(\\S+)\\s*$", TokenAuthScheme.NAME)
    );

    /**
     * Upload slice.
     */
    private final Slice origin;

    /**
     * Regular (token) authentication of the upload slice.
     */
    private final Slice fallback;

    /**
     * Upload tickets.
     */
    private final UploadTickets tickets;

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * WRITE permission check.
     */
    private final OperationControl write;

    /**
     * Ctor.
     * @param origin Upload slice
     * @param fallback Regular authentication of the upload slice
     * @param tickets Upload tickets
     * @param repo Repository name
     * @param write WRITE permission check
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    UploadAuthSlice(final Slice origin, final Slice fallback, final UploadTickets tickets,
        final String repo, final OperationControl write) {
        this.origin = origin;
        this.fallback = fallback;
        this.tickets = tickets;
        this.repo = repo;
        this.write = write;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return this.ticketUser(line, headers).thenCompose(
            user -> this.respond(line, headers, body, user)
        ).toCompletableFuture();
    }

    /**
     * Respond for the ticket user, if any.
     * @param line Request line
     * @param headers Request headers
     * @param body Request body
     * @param user Ticket user, empty without a valid ticket
     * @return Response
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> respond(final RequestLine line, final Headers headers,
        final Content body, final Optional<String> user) {
        final CompletableFuture<Response> res;
        if (user.isEmpty()) {
            res = this.fallback.response(line, headers, body);
        } else if (this.write.allowed(new AuthUser(user.get(), "conda-upload-ticket"))) {
            final Headers clean = new Headers();
            headers.stream()
                .filter(hdr -> !AuthzSlice.LOGIN_HDR.equalsIgnoreCase(hdr.getKey()))
                .forEach(clean::add);
            res = this.origin.response(line, clean.add(new Login(user.get())), body);
        } else {
            EcsLogger.warn("com.auto1.pantera.conda")
                .message("Conda upload ticket holder lacks WRITE on the repository")
                .eventCategory("authentication")
                .eventAction("conda_upload_ticket")
                .eventOutcome("failure")
                .field("repository.name", this.repo)
                .field("user.name", user.get())
                .log();
            res = body.discard().thenApply(ignored -> ResponseBuilder.forbidden().build());
        }
        return res;
    }

    /**
     * User of a valid upload ticket presented with this request.
     * @param line Request line
     * @param headers Request headers
     * @return User name if the request carries a ticket valid for its key
     */
    private CompletionStage<Optional<String>> ticketUser(final RequestLine line,
        final Headers headers) {
        final String path = line.uri().getPath();
        final Matcher pkg = UploadAuthSlice.PKG.matcher(path);
        return UploadAuthSlice.token(path, headers)
            .filter(tkn -> pkg.matches() && tkn.indexOf('.') > 0 && tkn.indexOf('.') == tkn.lastIndexOf('.'))
            .map(tkn -> this.tickets.redeem(tkn, this.repo, pkg.group(1)))
            .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
    }

    /**
     * Token presented with the request: {@code Authorization: token <t>} or
     * the {@code /t/<t>/} path segment.
     * @param path Request path
     * @param headers Request headers
     * @return Token if any
     */
    private static Optional<String> token(final String path, final Headers headers) {
        final Optional<String> header = headers.values(Authorization.NAME).stream()
            .findFirst()
            .map(UploadAuthSlice.HDR_TOKEN::matcher)
            .filter(Matcher::matches)
            .map(mtch -> mtch.group(1));
        final Optional<String> res;
        if (header.isPresent()) {
            res = header;
        } else {
            final Matcher matcher = UploadAuthSlice.PATH_TOKEN.matcher(path);
            if (matcher.matches()) {
                res = Optional.of(matcher.group(1));
            } else {
                res = Optional.empty();
            }
        }
        return res;
    }
}
