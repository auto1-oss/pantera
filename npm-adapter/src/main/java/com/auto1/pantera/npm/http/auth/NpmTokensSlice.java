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
package com.auto1.pantera.npm.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.model.NpmToken;
import com.auto1.pantera.npm.repository.TokenRepository;
import com.auto1.pantera.npm.security.TokenGenerator;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;

/**
 * {@code /-/npm/v1/tokens} — {@code npm token list/create/revoke}.
 *
 * <p>Backed by the same {@link TokenRepository} the {@code adduser} flow
 * already persists tokens through ({@code StorageTokenRepository} for
 * standalone and combined-non-JWT modes). JWT-only repositories (the mode
 * {@code RepositorySlices} wires for production {@code "npm"} repos today)
 * have no per-npm-token storage — tokens there are pure JWT — so this slice
 * answers honestly with 501 rather than silently returning an always-empty
 * list that would look like "you have no tokens" when the truth is "token
 * management isn't wired for this mode". See WS4-npm.9 in the 2.3.0 spec for
 * the JWT-integrated follow-up (deferred: it would require extending the
 * shared {@code Tokens} interface and {@code JwtTokens} in {@code pantera-main},
 * out of scope for a minimal, security-sensitive change here).</p>
 *
 * @since 2.3.0
 */
public final class NpmTokensSlice implements Slice {

    /**
     * Honest "not available" body for JWT-only repositories.
     */
    private static final String UNSUPPORTED_BODY =
        "{\"error\":\"npm token management is not available for JWT-only repositories\"}";

    /**
     * Backing token repository, empty for JWT-only repositories.
     */
    private final Optional<TokenRepository> tokens;

    /**
     * Token generator.
     */
    private final TokenGenerator generator;

    /**
     * Ctor backed by a real token repository.
     *
     * @param tokens Token repository
     */
    public NpmTokensSlice(final TokenRepository tokens) {
        this(Optional.of(tokens));
    }

    /**
     * Ctor for JWT-only repositories — no token storage is wired.
     */
    public NpmTokensSlice() {
        this(Optional.empty());
    }

    private NpmTokensSlice(final Optional<TokenRepository> tokens) {
        this.tokens = tokens;
        this.generator = new TokenGenerator();
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (this.tokens.isEmpty()) {
            return body.asBytesFuture().thenApply(
                ignored -> ResponseBuilder.from(RsStatus.NOT_IMPLEMENTED)
                    .jsonBody(UNSUPPORTED_BODY)
                    .build()
            );
        }
        final TokenRepository repo = this.tokens.get();
        final String owner = new Login(headers).getValue();
        return switch (line.method()) {
            case GET -> this.list(repo, owner, body);
            case POST -> this.create(repo, owner, body);
            case DELETE -> this.revoke(repo, owner, line, body);
            default -> body.asBytesFuture().thenApply(
                ignored -> ResponseBuilder.methodNotAllowed().build()
            );
        };
    }

    private CompletableFuture<Response> list(
        final TokenRepository repo, final String owner, final Content body
    ) {
        return body.asBytesFuture().thenCompose(
            ignored -> repo.findByUsername(owner).thenApply(found -> {
                final JsonArrayBuilder objects = Json.createArrayBuilder();
                for (final NpmToken token : found) {
                    objects.add(
                        Json.createObjectBuilder()
                            .add("key", token.id())
                            .add("token", NpmTokensSlice.mask(token.token()))
                            .add("created", token.createdAt().toString())
                            .add("updated", token.createdAt().toString())
                            .add("readonly", false)
                            .add("automation", false)
                            .add("cidr_whitelist", Json.createArrayBuilder().build())
                            .build()
                    );
                }
                final JsonObject out = Json.createObjectBuilder()
                    .add("objects", objects.build())
                    .add("total", found.size())
                    .add("urls", Json.createObjectBuilder().build())
                    .build();
                return ResponseBuilder.ok().jsonBody(out).build();
            })
        );
    }

    private CompletableFuture<Response> create(
        final TokenRepository repo, final String owner, final Content body
    ) {
        return body.asBytesFuture().thenCompose(
            ignored -> this.generator.generate(owner).thenCompose(repo::save).thenApply(saved -> {
                EcsLogger.info("com.auto1.pantera.npm")
                    .message("npm token created")
                    .eventCategory("configuration")
                    .eventAction("npm_token_create")
                    .eventOutcome("success")
                    .field("user.name", owner)
                    .field("log.source", "application")
                    .log();
                final JsonObject out = Json.createObjectBuilder()
                    .add("token", saved.token())
                    .add("key", saved.id())
                    .add("created", saved.createdAt().toString())
                    .add("readonly", false)
                    .add("automation", false)
                    .add("cidr_whitelist", Json.createArrayBuilder().build())
                    .build();
                return ResponseBuilder.created().jsonBody(out).build();
            })
        );
    }

    private CompletableFuture<Response> revoke(
        final TokenRepository repo, final String owner, final RequestLine line, final Content body
    ) {
        return body.asBytesFuture().thenCompose(ignored -> {
            final Optional<String> id = NpmTokensSlice.tokenId(line.uri().getPath());
            if (id.isEmpty()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.badRequest().jsonBody("{\"error\":\"missing token id\"}").build()
                );
            }
            return repo.findByUsername(owner).thenCompose(owned -> {
                final boolean belongsToCaller = owned.stream()
                    .anyMatch(token -> token.id().equals(id.get()));
                if (!belongsToCaller) {
                    return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                }
                return repo.delete(id.get()).thenApply(ignored2 -> {
                    EcsLogger.info("com.auto1.pantera.npm")
                        .message("npm token revoked")
                        .eventCategory("configuration")
                        .eventAction("npm_token_revoke")
                        .eventOutcome("success")
                        .field("user.name", owner)
                        .field("log.source", "application")
                        .log();
                    return ResponseBuilder.ok().jsonBody("{\"ok\":true}").build();
                });
            });
        });
    }

    private static Optional<String> tokenId(final String path) {
        final int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) {
            return Optional.empty();
        }
        final String candidate = path.substring(idx + 1);
        return candidate.isEmpty() ? Optional.empty() : Optional.of(candidate);
    }

    private static String mask(final String token) {
        final String masked;
        if (token.length() <= 6) {
            masked = token;
        } else {
            masked = token.substring(0, 6) + "...";
        }
        return masked;
    }
}
