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
     * Which realms may receive {@link #auth}; null = always (the caller
     * supplied an explicit token-request authenticator and owns that
     * decision).
     */
    private final RealmTrust trust;

    /**
     * Ctor with an explicit token-request authenticator that is used for
     * every realm — the caller owns that decision.
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
        this(client, format, auth, null);
    }

    /**
     * Ctor binding the token-request credentials to trusted realms only.
     *
     * <p>SECURITY (2.2.9): the realm is upstream-controlled. {@code auth}
     * (the configured upstream credentials) is released only to a realm
     * {@code trust} accepts; any other realm gets an anonymous token
     * request — never the credentials of a different upstream.</p>
     *
     * @param client Client slices.
     * @param format Token format.
     * @param auth Token request authenticator.
     * @param trust Realm trust (nullable: trust every realm)
     */
    public BearerAuthenticator(
        final ClientSlices client,
        final TokenFormat format,
        final Authenticator auth,
        final RealmTrust trust
    ) {
        this.client = client;
        this.format = format;
        this.auth = auth;
        this.trust = trust;
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

        return new AuthClientSlice(new UriClientSlice(this.client, realm), this.tokenAuth(realm))
            .response(new RequestLine(RqMethod.GET, "?" + query), Headers.EMPTY, Content.EMPTY)
            .thenCompose(response -> response.body().asBytesFuture())
            .thenApply(bytes -> {
                try {
                    String token = this.format.token(bytes);
                    return new Authorization.Bearer(token);
                } catch (final UpstreamAuthDeniedException denied) {
                    // Expected outcome: the upstream replied with an OAuth
                    // Registry V2 error envelope (e.g. anonymous access
                    // denied on GCR / DHI). Logging at INFO so SREs can
                    // still see which upstream denied us when triaging,
                    // but without flagging it as a surprising parse
                    // failure — the group resolver handles the denial
                    // by falling through to the next member.
                    com.auto1.pantera.http.log.EcsLogger
                        .info("com.auto1.pantera.http.client.auth")
                        .message("Upstream denied bearer-token request; realm="
                            + realm + " " + denied.getMessage())
                        .eventCategory("authentication")
                        .eventAction("bearer_token_denied")
                        .eventOutcome("failure")
                        .field("log.source", "application")
                        .log();
                    throw denied;
                } catch (final RuntimeException ex) {
                    // The token response can carry a real token or the
                    // upstream's error detail — never echo its body into the
                    // log stream (2.2.9). Size and the leading byte class are
                    // enough to tell a breaker 502 ('U'pstream...) or an HTML
                    // block page from JSON.
                    final int total = bytes == null ? -1 : bytes.length;
                    final String lead = bytes == null || bytes.length == 0 ? "<empty>"
                        : Character.isLetter(bytes[0]) ? "text" : bytes[0] == '{' ? "json" : "binary";
                    com.auto1.pantera.http.log.EcsLogger
                        .warn("com.auto1.pantera.http.client.auth")
                        .message("Bearer token response parse failed; realm="
                            + realm + " bodyKind=" + lead + " totalBytes=" + total)
                        .eventCategory("authentication")
                        .eventAction("bearer_token_parse")
                        .eventOutcome("failure")
                        .field("log.source", "application")
                        .error(ex)
                        .log();
                    throw ex;
                }
            });
    }

    /**
     * The authenticator for the token request: the configured credentials
     * when the realm is trusted, anonymous otherwise (logged, so a
     * credential-harvesting realm is visible in the log stream).
     *
     * @param realm Realm named by the challenge
     * @return Authenticator to use for the token request
     */
    private Authenticator tokenAuth(final URI realm) {
        if (this.trust == null || this.auth == Authenticator.ANONYMOUS || this.trust.trusts(realm)) {
            return this.auth;
        }
        com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.http.client.auth")
            .message("Bearer realm is not the configured upstream; requesting token anonymously")
            .eventCategory("authentication")
            .eventAction("bearer_realm_untrusted")
            .eventOutcome("failure")
            .field("url.full", realm.toString())
            .field("destination.address", realm.getHost())
            .field("event.reason", "realm_origin_untrusted")
            .field("log.source", "application")
            .log();
        return Authenticator.ANONYMOUS;
    }
}
