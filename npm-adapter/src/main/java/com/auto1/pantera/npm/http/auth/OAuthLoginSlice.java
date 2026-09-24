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
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.AuthWorkerPool;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.LogSanitizer;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import java.io.StringReader;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;

/**
 * Legacy {@code npm login} / {@code npm adduser}: the CouchDB-style
 * {@code PUT /-/user/org.couchdb.user:<name>} with the user name and
 * password in the JSON body and no {@code Authorization} header.
 *
 * <p>The password is validated through Pantera authentication and, on
 * success, the answer is {@code 201 {"ok":true,"token":...}} carrying a
 * Pantera API token issued by {@link Tokens#issueApiToken} (named, expiring,
 * revocable, bounded by the admin token lifetime policy). npm stores that
 * token as the registry's {@code _authToken}. A wrong password answers
 * {@code 401}; a body without a name and password answers {@code 400}.
 * Nothing but a token minted for the authenticated user is ever returned.</p>
 *
 * <p>The password check and the token store are blocking, so both run on
 * the shared auth worker pool, never on the event loop.</p>
 *
 * <p>The request is reachable without credentials, so its body is capped by
 * {@link LoginBodyCapSlice} before it is buffered: a body declared larger
 * than {@link LoginBodyCapSlice#MAX_BODY_BYTES} is refused without being
 * read, and a streamed body is metered and cancelled the moment it crosses
 * the cap. Both answer {@code 413} with a JSON error and never reach the
 * credentials check.</p>
 *
 * @since 1.2
 */
public final class OAuthLoginSlice implements Slice {

    /**
     * The legacy login / adduser request this slice answers. It carries its
     * credentials in the body, so it must pass the anonymous-access gate
     * without an {@code Authorization} header.
     */
    public static final RtRule LEGACY_LOGIN = new RtRule.All(
        MethodRule.PUT, new RtRule.ByPath(".*/-/user/org\\.couchdb\\.user:[^/]+$")
    );

    /**
     * The web login request ({@code npm login --auth-type=web}, npm's
     * default). Pantera has no browser login flow for npm: it is declined
     * with a 4xx, on which npm falls back to {@link #LEGACY_LOGIN}.
     */
    public static final RtRule WEB_LOGIN = new RtRule.All(
        MethodRule.POST, new RtRule.ByPath(".*/-/v1/login$")
    );

    /**
     * The {@code npm logout} request ({@code DELETE /-/user/token/<token>}).
     * Pantera does not revoke tokens through the npm registry API: every
     * repository mode declines it the same way (404,
     * {@code X-Pantera-Reason: not_implemented}).
     */
    public static final RtRule LOGOUT = new RtRule.All(
        MethodRule.DELETE, new RtRule.ByPath(".*/-/user/token/[^/]+$")
    );

    /**
     * Both credential-bootstrap requests; neither needs prior credentials.
     */
    public static final RtRule CREDENTIAL_BOOTSTRAP = new RtRule.Any(
        OAuthLoginSlice.LEGACY_LOGIN, OAuthLoginSlice.WEB_LOGIN
    );

    /**
     * Label of the API tokens this slice issues.
     */
    static final String TOKEN_LABEL = "npm login";

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.npm";

    /**
     * JSON key of the error message.
     */
    private static final String ERROR = "error";

    /**
     * Authentication that validates the password.
     */
    private final Authentication auth;

    /**
     * Token service that issues the registry token.
     */
    private final Tokens tokens;

    /**
     * Constructor.
     * @param auth Authentication that validates credentials
     * @param tokens Token service for issuing registry tokens
     */
    public OAuthLoginSlice(final Authentication auth, final Tokens tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return new LoginBodyCapSlice(
            (rql, rqh, bounded) -> bounded.asStringFuture().thenCompose(this::answer)
        ).response(line, headers, body);
    }

    /**
     * Answer a login body that fits the cap.
     * @param text Body
     * @return Completion with the response
     */
    private CompletableFuture<Response> answer(final String text) {
        final Optional<JsonObject> json = OAuthLoginSlice.parse(text);
        final String name = json.map(obj -> OAuthLoginSlice.string(obj, "name"))
            .orElse("");
        final String password = json.map(obj -> OAuthLoginSlice.string(obj, "password"))
            .orElse("");
        final CompletableFuture<Response> result;
        if (name.isEmpty() || password.isEmpty()) {
            result = CompletableFuture.completedFuture(
                ResponseBuilder.badRequest()
                    .jsonBody(OAuthLoginSlice.error("name and password are required"))
                    .build()
            );
        } else {
            result = CompletableFuture.supplyAsync(
                () -> this.login(name, password), AuthWorkerPool.AUTH_EXECUTOR
            );
        }
        return result;
    }

    /**
     * Validate the password and issue the token. Blocking.
     * @param name User name
     * @param password Password
     * @return Response
     */
    private Response login(final String name, final String password) {
        final Optional<AuthUser> user = this.auth.user(name, password);
        final Response response;
        if (user.isEmpty()) {
            EcsLogger.warn(OAuthLoginSlice.LOGGER)
                .message("npm login failed: invalid credentials")
                .eventCategory("authentication")
                .eventAction("npm_login")
                .eventOutcome("failure")
                .field("user.name", name)
                .field("log.source", "application")
                .log();
            response = ResponseBuilder.unauthorized()
                .jsonBody(OAuthLoginSlice.error("invalid credentials"))
                .build();
        } else {
            response = this.issue(user.get(), name);
        }
        return response;
    }

    /**
     * Issue the registry token for an authenticated user. Blocking.
     * @param user Authenticated user
     * @param name User name the client logged in as
     * @return Response
     */
    private Response issue(final AuthUser user, final String name) {
        Response response;
        try {
            if (this.tokens == null) {
                throw new IllegalStateException("no token service is configured");
            }
            final String token = this.tokens.issueApiToken(user, OAuthLoginSlice.TOKEN_LABEL);
            EcsLogger.info(OAuthLoginSlice.LOGGER)
                .message("npm login succeeded: API token issued")
                .eventCategory("authentication")
                .eventAction("npm_login")
                .eventOutcome("success")
                .field("user.name", user.name())
                .field("log.source", "application")
                .log();
            response = ResponseBuilder.created()
                .jsonBody(
                    Json.createObjectBuilder()
                        .add("ok", true)
                        .add("id", "org.couchdb.user:" + name)
                        .add("token", token)
                        .build()
                )
                .build();
        } catch (final RuntimeException err) {
            // The password and token are in scope: log only the sanitized
            // exception class and message, never the throwable bundle.
            EcsLogger.error(OAuthLoginSlice.LOGGER)
                .message("npm login failed: could not issue an API token")
                .eventCategory("authentication")
                .eventAction("npm_login")
                .eventOutcome("failure")
                .field("user.name", user.name())
                .field("error.type", err.getClass().getSimpleName())
                .field("error.message", LogSanitizer.sanitizeMessage(err.getMessage()))
                .field("log.source", "application")
                .log();
            response = ResponseBuilder.from(RsStatus.INTERNAL_ERROR)
                .jsonBody(OAuthLoginSlice.error("could not issue a token"))
                .build();
        }
        return response;
    }

    /**
     * Parse the body as a JSON object.
     * @param text Body
     * @return JSON object, or empty when the body is not one
     */
    private static Optional<JsonObject> parse(final String text) {
        Optional<JsonObject> json;
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            json = Optional.of(reader.readObject());
        } catch (final JsonException | IllegalStateException err) {
            json = Optional.empty();
        }
        return json;
    }

    /**
     * A string member of a JSON object, or empty.
     * @param json JSON object
     * @param key Member name
     * @return Value
     */
    private static String string(final JsonObject json, final String key) {
        final JsonValue value = json.get(key);
        String result = "";
        if (value != null && value.getValueType() == JsonValue.ValueType.STRING) {
            result = json.getString(key);
        }
        return result;
    }

    /**
     * npm-style error body.
     * @param message Message
     * @return JSON object
     */
    private static JsonObject error(final String message) {
        return Json.createObjectBuilder().add(OAuthLoginSlice.ERROR, message).build();
    }
}
