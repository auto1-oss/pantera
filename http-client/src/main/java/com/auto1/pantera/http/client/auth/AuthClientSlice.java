/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.RemoteConfig;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Slice augmenting requests with authentication when needed.
 */
public final class AuthClientSlice implements Slice {

    public static AuthClientSlice withClientSlice(ClientSlices client, RemoteConfig cfg) {
        return new AuthClientSlice(
            client.from(cfg.uri().toString()),
            GenericAuthenticator.create(client, cfg.username(), cfg.pwd())
        );
    }

    public static AuthClientSlice withUriClientSlice(ClientSlices client, RemoteConfig cfg) {
        return new AuthClientSlice(
            new UriClientSlice(client, cfg.uri()),
            GenericAuthenticator.create(client, cfg.username(), cfg.pwd())
        );
    }

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Authenticator.
     */
    private final Authenticator auth;

    /**
     * @param origin Origin slice.
     * @param auth Authenticator.
     */
    public AuthClientSlice(Slice origin, Authenticator auth) {
        this.origin = origin;
        this.auth = auth;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return body.asBytesFuture()
            .thenApply(data -> {
                Content copyContent = new Content.From(Arrays.copyOf(data, data.length));
                return this.auth.authenticate(Headers.EMPTY)
                    .toCompletableFuture()
                    .thenCompose(
                        authFirst -> this.origin.response(
                                line, headers.copy().addAll(authFirst), copyContent
                            ).thenApply(
                                response -> {
                                    if (response.status() == RsStatus.UNAUTHORIZED) {
                                        return this.auth.authenticate(response.headers())
                                            .thenCompose(
                                                authSecond -> {
                                                    if (authSecond.isEmpty()) {
                                                        return CompletableFuture.completedFuture(response);
                                                    }
                                                    return this.origin.response(
                                                        line, headers.copy().addAll(authSecond), copyContent
                                                    );
                                                }
                                            );
                                    }
                                    return CompletableFuture.completedFuture(response);
                                })
                            .thenCompose(Function.identity())
                    );
            }).thenCompose(Function.identity());
    }
}
