/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
import com.auto1.pantera.http.rq.RequestLine;
import com.google.common.base.Strings;

import java.util.concurrent.CompletableFuture;

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
            return new BasicAuthScheme(this.auth)
                .authenticate(headers)
                .toCompletableFuture()
                .thenApply(
                    authResult -> {
                        assert authResult.status() != AuthScheme.AuthStatus.FAILED;
                        final String token = this.tokens.generate(authResult.user());
                        if (Strings.isNullOrEmpty(token)) {
                            return ResponseBuilder.notFound()
                                .textBody(String.format(UsersEntity.URI_S_NOT_FOUND, line.uri()))
                                .build();

                        }
                        return ResponseBuilder.ok().textBody(token).build();
                    }
                );
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
