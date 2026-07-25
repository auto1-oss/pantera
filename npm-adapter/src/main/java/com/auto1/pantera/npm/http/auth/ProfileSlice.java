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
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.model.User;
import com.auto1.pantera.npm.repository.UserRepository;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObjectBuilder;

/**
 * {@code /-/npm/v1/user} — {@code npm profile get}. Reads the authenticated
 * username the same way {@code whoami} does (the {@code pantera_login}
 * header the auth wrap sets after a successful login), so it works
 * regardless of auth mode (JWT-only, combined, or standalone npm tokens).
 *
 * <p>{@code PUT}/{@code POST} (profile update) is a deliberate 200 no-op:
 * Pantera has no per-npm-adapter profile fields beyond username/email to
 * persist for JWT-authenticated users (those live in the shared user store,
 * out of scope for this adapter), so the honest answer is "nothing to
 * change here" rather than fabricating a write that did not happen.</p>
 *
 * @since 2.3.0
 */
public final class ProfileSlice implements Slice {

    /**
     * Backing user repository — present only in standalone (non-JWT) mode,
     * where {@code StorageUserRepository} tracks an email address.
     */
    private final Optional<UserRepository> users;

    /**
     * Ctor backed by a real user repository (standalone mode).
     *
     * @param users User repository
     */
    public ProfileSlice(final UserRepository users) {
        this(Optional.of(users));
    }

    /**
     * Ctor for JWT-backed modes — no local user record to enrich the profile.
     */
    public ProfileSlice() {
        this(Optional.empty());
    }

    private ProfileSlice(final Optional<UserRepository> users) {
        this.users = users;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final String owner = new Login(headers).getValue();
        return body.asBytesFuture().thenCompose(ignored -> {
            if (this.users.isEmpty()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().jsonBody(ProfileSlice.profileJson(owner, null)).build()
                );
            }
            return this.users.get().findByUsername(owner).thenApply(
                found -> ResponseBuilder.ok()
                    .jsonBody(ProfileSlice.profileJson(owner, found.map(User::email).orElse(null)))
                    .build()
            );
        });
    }

    private static String profileJson(final String owner, final String email) {
        final JsonObjectBuilder builder = Json.createObjectBuilder()
            .add("name", owner)
            .add("tfa", false);
        if (email != null && !email.isEmpty()) {
            builder.add("email", email);
        }
        return builder.build().toString();
    }
}
