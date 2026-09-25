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
package  com.auto1.pantera.conan.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.google.common.base.Strings;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Conan /v1/users/* REST APIs. For now minimally implemented, just for package uploading support.
 */
public final class UsersEntity {

    /**
     * Pattern for /authenticate request.
     */
    public static final PathWrap USER_AUTH_PATH = new PathWrap.UserAuth();

    /**
     * Pattern for /check_credentials request.
     */
    public static final PathWrap CREDS_CHECK_PATH = new PathWrap.CredsCheck();

    /**
     * Error message string for the client.
     */
    private static final String URI_S_NOT_FOUND = "URI %s not found.";

    /**
     * HTTP Content-type header name.
     */
    private static final String CONTENT_TYPE = "Content-Type";

    /**
     * HTTP json application type string.
     */
    private static final String JSON_TYPE = "application/json";

    private UsersEntity() {
    }

    /**
     * Conan /authenticate REST APIs.
     */
    public static final class UserAuth implements Slice {

        /**
         * Shape of a bearer token presented as the Basic password: three
         * base64url segments separated by dots. Such a value is a JWT, not a
         * password, and must not be exchanged for a freshly minted token.
         */
        private static final Pattern JWT_SHAPE =
            Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

        /**
         * Current auth implemenation.
         */
        private final Authentication auth;

        /**
         * User token generator.
         */
        private final Tokens tokens;

        /**
         * @param auth Login authentication for the user.
         * @param tokens Auth. token genrator for the user.
         */
        public UserAuth(Authentication auth, Tokens tokens) {
            this.auth = auth;
            this.tokens = tokens;
        }

        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            if (UserAuth.basicPassword(headers).filter(UserAuth::looksLikeJwt).isPresent()) {
                // A bearer token presented as the Basic password would pass the
                // jwt-password auth provider and be exchanged for a freshly
                // minted token that outlives revocation of the original — refuse.
                return CompletableFuture.completedFuture(UserAuth.unauthorized());
            }
            return new BasicAuthScheme(this.auth)
                .authenticate(headers)
                .toCompletableFuture()
                .thenApply(authResult -> this.token(line, authResult));
        }

        /**
         * Build the token response for an authentication result. A token is
         * minted only for an authenticated, non-anonymous principal; an
         * unauthenticated, failed or anonymous request is refused with 401
         * instead of being handed a usable token.
         *
         * @param line Request line (for the not-found message).
         * @param authResult Authentication result.
         * @return Response.
         */
        private Response token(final RequestLine line, final AuthScheme.Result authResult) {
            final Response response;
            if (authResult.status() != AuthScheme.AuthStatus.AUTHENTICATED
                || authResult.user() == null || authResult.user().isAnonymous()) {
                response = UserAuth.unauthorized();
            } else {
                final String token = this.tokens.generate(authResult.user());
                if (Strings.isNullOrEmpty(token)) {
                    response = ResponseBuilder.notFound()
                        .textBody(String.format(UsersEntity.URI_S_NOT_FOUND, line.uri()))
                        .build();
                } else {
                    response = ResponseBuilder.ok().textBody(token).build();
                }
            }
            return response;
        }

        /**
         * Password from a Basic {@code Authorization} header, if present and
         * decodable. Empty for a missing, scheme-less, non-Basic or undecodable
         * header.
         *
         * @param headers Request headers.
         * @return Basic password, or empty.
         */
        private static Optional<String> basicPassword(final Headers headers) {
            return new RqHeaders(headers, Authorization.NAME).stream()
                .findFirst()
                .map(Authorization::new)
                .filter(Authorization::parseable)
                .filter(atz -> BasicAuthScheme.NAME.equals(atz.scheme()))
                .flatMap(UserAuth::decodePassword);
        }

        /**
         * Decode the password from a parseable Basic authorization, treating an
         * undecodable value (bad Base64 or no {@code ':'}) as "no password".
         *
         * @param atz Parseable Basic authorization.
         * @return Password, or empty when undecodable.
         */
        private static Optional<String> decodePassword(final Authorization atz) {
            try {
                return Optional.ofNullable(
                    new Authorization.Basic(atz.credentials()).password()
                );
            } catch (final IllegalArgumentException ex) {
                return Optional.empty();
            }
        }

        /**
         * Whether a supplied secret is shaped like a JWT: three base64url
         * segments separated by dots.
         *
         * @param value Candidate password.
         * @return {@code true} when it has the three-segment JWT shape.
         */
        private static boolean looksLikeJwt(final String value) {
            return value != null && UserAuth.JWT_SHAPE.matcher(value).matches();
        }

        /**
         * The 401 refusal shared by the token-as-password and the
         * unauthenticated paths.
         *
         * @return Unauthorized response.
         */
        private static Response unauthorized() {
            return ResponseBuilder.unauthorized()
                .textBody("authentication required")
                .build();
        }
    }

    /**
     * Conan /check_credentials REST APIs.
     * @since 0.1
     */
    public static final class CredsCheck implements Slice {

        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            // todo выглядит так, будто здесь ничего не происходит credsCheck returns "{}"

            return CompletableFuture.supplyAsync(line::uri)
                .thenCompose(
                    uri -> CredsCheck.credsCheck().thenApply(
                        content -> {
                            if (Strings.isNullOrEmpty(content)) {
                                return ResponseBuilder.notFound()
                                    .textBody(String.format(UsersEntity.URI_S_NOT_FOUND, uri))
                                    .build();
                            }
                            return ResponseBuilder.ok()
                                .header(UsersEntity.CONTENT_TYPE, UsersEntity.JSON_TYPE)
                                .textBody(content)
                                .build();
                        }
                )
            );
        }

        /**
         * Checks user credentials for Conan HTTP request.
         * @return Json string response.
         */
        private static CompletableFuture<String> credsCheck() {
            return CompletableFuture.completedFuture("{}");
        }
    }
}
