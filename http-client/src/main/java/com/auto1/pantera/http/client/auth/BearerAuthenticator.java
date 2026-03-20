/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Bearer authenticator using specified authenticator and format to get required token.
 */
public final class BearerAuthenticator implements Authenticator {

    /**
     * Client slices.
     */
    private final ClientSlices client;

    /**
     * Token format.
     */
    private final TokenFormat format;

    /**
     * Token request authenticator.
     */
    private final Authenticator auth;

    /**
     * Ctor.
     *
     * @param client Client slices.
     * @param format Token format.
     * @param auth Token request authenticator.
     */
    public BearerAuthenticator(
        final ClientSlices client,
        final TokenFormat format,
        final Authenticator auth
    ) {
        this.client = client;
        this.format = format;
        this.auth = auth;
    }

    @Override
    public CompletionStage<Headers> authenticate(final Headers headers) {
        final Optional<WwwAuthenticate> challenge =
            StreamSupport.stream(headers.spliterator(), false)
                .filter(header -> WwwAuthenticate.NAME.equalsIgnoreCase(header.getKey()))
                .map(header -> new WwwAuthenticate(header.getValue()))
                .filter(auth -> "Bearer".equalsIgnoreCase(auth.scheme()))
                .findFirst();
        return challenge
            .map(this::authenticate)
            .orElseThrow(() -> new IllegalStateException("Bearer challenge was not found"))
            .thenApply(Headers::from);
    }

    /**
     * Creates 'Authorization' header using requirements from 'WWW-Authenticate'.
     *
     * @param header WWW-Authenticate header.
     * @return Authorization header.
     */
    private CompletableFuture<Authorization.Bearer> authenticate(final WwwAuthenticate header) {
        final URI realm;
        try {
            realm = new URI(header.realm());
        } catch (final URISyntaxException ex) {
            throw new IllegalArgumentException(ex);
        }
        final String query = header.params().stream()
            .filter(param -> !"realm".equals(param.name()))
            .map(param -> String.format("%s=%s", param.name(), param.value()))
            .collect(Collectors.joining("&"));

        return new AuthClientSlice(new UriClientSlice(this.client, realm), this.auth)
            .response(new RequestLine(RqMethod.GET, "?" + query), Headers.EMPTY, Content.EMPTY)
            .thenCompose(response -> response.body().asBytesFuture())
            .thenApply(bytes -> {
                String token = this.format.token(bytes);
                return new Authorization.Bearer(token);
            });
    }
}
