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
package com.auto1.pantera.conan.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.conan.ItemTokenizer;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqParams;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Authorisation of a Conan file PUT.
 *
 * <p>Conan 1.x deliberately sends no {@code Authorization} header to a URL
 * that carries a {@code signature} query parameter (it treats such a URL as
 * a pre-signed file-server URL). The upload URLs Pantera issues are signed
 * for the authenticated user who requested them, one repository, one path,
 * one host and one hour, so a credential-less PUT is authorised as that user,
 * who must still hold WRITE on this repository when the file arrives. A PUT
 * that does carry credentials is authorised by them as before.</p>
 *
 * @since 2.2.9
 */
final class SignedPutSlice implements Slice {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.conan";

    /**
     * File upload, which also checks the signature's repository, host and path.
     */
    private final Slice put;

    /**
     * The upload behind the regular credential check.
     */
    private final Slice authenticated;

    /**
     * Upload URL signatures.
     */
    private final ItemTokenizer tokenizer;

    /**
     * WRITE permission check.
     */
    private final OperationControl write;

    /**
     * Repository name.
     */
    private final String repository;

    /**
     * Ctor.
     * @param put File upload
     * @param authenticated The upload behind the regular credential check
     * @param tokenizer Upload URL signatures
     * @param write WRITE permission check
     * @param repository Repository name
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    SignedPutSlice(final Slice put, final Slice authenticated, final ItemTokenizer tokenizer,
        final OperationControl write, final String repository) {
        this.put = put;
        this.authenticated = authenticated;
        this.tokenizer = tokenizer;
        this.write = write;
        this.repository = repository;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<Response> res;
        final Optional<String> signature = new RqParams(line.uri()).value("signature");
        if (!headers.values(Authorization.NAME).isEmpty()) {
            res = this.authenticated.response(line, headers, body);
        } else if (signature.isEmpty()) {
            res = SignedPutSlice.refused(body, ResponseBuilder.unauthorized().build());
        } else {
            res = this.tokenizer.authenticateToken(signature.get())
                .toCompletableFuture()
                .thenCompose(
                    item -> this.respond(
                        item.filter(inf -> this.repository.equals(inf.getRepository()))
                            .flatMap(ItemTokenizer.ItemInfo::user),
                        line, headers, body
                    )
                );
        }
        return res;
    }

    /**
     * Upload as the user the signature was issued to.
     * @param user Signature user, empty for no valid signature of this repository
     * @param line Request line
     * @param headers Request headers
     * @param body Request body
     * @return Response
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> respond(final Optional<String> user,
        final RequestLine line, final Headers headers, final Content body) {
        final CompletableFuture<Response> res;
        if (user.isEmpty()) {
            res = SignedPutSlice.refused(body, ResponseBuilder.unauthorized().build());
        } else if (this.write.allowed(new AuthUser(user.get(), "conan-upload-url"))) {
            final Headers clean = new Headers();
            headers.stream()
                .filter(hdr -> !AuthzSlice.LOGIN_HDR.equalsIgnoreCase(hdr.getKey()))
                .forEach(clean::add);
            res = this.put.response(line, clean.add(AuthzSlice.LOGIN_HDR, user.get()), body);
        } else {
            EcsLogger.warn(SignedPutSlice.LOGGER)
                .message("Conan upload URL holder lacks WRITE on the repository")
                .eventCategory("authentication")
                .eventAction("conan_signed_upload")
                .eventOutcome("failure")
                .field("repository.name", this.repository)
                .field("user.name", user.get())
                .log();
            res = SignedPutSlice.refused(body, ResponseBuilder.forbidden().build());
        }
        return res;
    }

    /**
     * Refuse the request, draining (never materialising) its body.
     * @param body Request body
     * @param rsp Refusal
     * @return Response
     */
    private static CompletableFuture<Response> refused(final Content body, final Response rsp) {
        return body.discard().thenApply(ignored -> rsp);
    }
}
