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
package com.auto1.pantera.http.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.MDC;

/**
 * One check of a user name and password against a sign-in chain, with the
 * {@link AuthRefusal} the chain noted when it refused the login.
 *
 * <p>Every entry point that checks a password goes through this class, so a
 * refusal is read on the thread the chain ran on, belongs to this login
 * alone, and never stays on a pooled thread for the next request. For the
 * same reason the chain runs under the logging context of its own login,
 * and the thread gets back the context it had.</p>
 *
 * @since 2.2.15
 */
public final class PasswordCheck {

    /**
     * Sign-in chain.
     */
    private final Authentication auth;

    /**
     * Headers of the request that carries the login.
     */
    private final Headers request;

    /**
     * Where the login comes from.
     */
    private final LoginSource source;

    /**
     * Ctor.
     *
     * @param auth Sign-in chain
     */
    public PasswordCheck(final Authentication auth) {
        this(auth, Headers.EMPTY, LoginSource.UNKNOWN);
    }

    /**
     * Ctor.
     *
     * @param auth Sign-in chain
     * @param request Headers of the request that carries the login
     */
    public PasswordCheck(final Authentication auth, final Headers request) {
        this(
            auth, request,
            new LoginSource(
                PasswordCheck.first(request, EcsLoggingSlice.CTX_CLIENT_IP_HEADER),
                PasswordCheck.first(request, "User-Agent"),
                LoginSource.REPOSITORY
            )
        );
    }

    /**
     * Ctor for an entry point that is not a repository request.
     *
     * @param auth Sign-in chain
     * @param source Where the login comes from
     */
    public PasswordCheck(final Authentication auth, final LoginSource source) {
        this(auth, Headers.EMPTY, source);
    }

    /**
     * Primary ctor.
     *
     * @param auth Sign-in chain
     * @param request Headers of the request that carries the login
     * @param source Where the login comes from
     */
    private PasswordCheck(
        final Authentication auth, final Headers request, final LoginSource source
    ) {
        this.auth = auth;
        this.request = request;
        this.source = source;
    }

    /**
     * Check on the shared sign-in pool: the chain may ask a database or an
     * identity provider and must not block the calling thread.
     *
     * @param username User name
     * @param password Password
     * @return Outcome
     */
    public CompletableFuture<Outcome> run(final String username, final String password) {
        return CompletableFuture.supplyAsync(
            () -> this.now(username, password), AuthWorkerPool.AUTH_EXECUTOR
        );
    }

    /**
     * Check on the calling thread.
     *
     * @param username User name
     * @param password Password
     * @return Outcome
     */
    public Outcome now(final String username, final String password) {
        final Map<String, String> prior = MDC.getCopyOfContextMap();
        try {
            this.bind(username);
            this.source.bind();
            return this.checked(username, password);
        } finally {
            LoginSource.unbind();
            if (prior == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(prior);
            }
        }
    }

    /**
     * Put the logging context of this login on the thread: the user name
     * being checked, and the client address and trace of its request.
     *
     * <p>The thread is pooled, and the context it was started with is the
     * one of the thread that submitted the check. After a token check that
     * ended on a shared thread, that is whatever an earlier request left
     * there. The logger prefers the thread's context to the fields a
     * record names, so without this the chain's records would carry
     * another request's user, address and trace.</p>
     *
     * @param username User name being checked
     */
    private void bind(final String username) {
        if (!this.request.find(EcsLoggingSlice.CTX_TRACE_ID_HEADER).isEmpty()) {
            // A request of the standard pipeline says everything about
            // itself: nothing of the submitting thread's context is kept.
            MDC.clear();
            RequestContextHeaders.bindToMdc(this.request);
        }
        if (username == null || username.isEmpty()) {
            MDC.remove(EcsMdc.USER_NAME);
        } else {
            MDC.put(EcsMdc.USER_NAME, username);
        }
    }

    private static String first(final Headers headers, final String name) {
        return headers.find(name).stream().findFirst().map(hdr -> hdr.getValue()).orElse("");
    }

    /**
     * Ask the chain and collect the refusal it noted.
     *
     * @param username User name
     * @param password Password
     * @return Outcome
     */
    private Outcome checked(final String username, final String password) {
        // Whatever earlier work left on this pooled thread is not about
        // this login.
        AuthRefusal.take();
        final Optional<AuthUser> user;
        try {
            user = this.auth.user(username, password);
        } catch (final RuntimeException | Error err) {
            AuthRefusal.take();
            throw err;
        }
        final Optional<AuthRefusal> refusal = AuthRefusal.take();
        final Outcome outcome;
        if (user.isPresent()) {
            // A provider that refused does not speak for a chain in which
            // another provider accepted.
            outcome = new Outcome(user, Optional.empty());
        } else {
            outcome = new Outcome(user, refusal);
        }
        return outcome;
    }

    /**
     * Outcome of a check.
     *
     * @param user The user, when the login was accepted
     * @param refusal Why it was refused, when the chain said so
     * @since 2.2.15
     */
    public record Outcome(Optional<AuthUser> user, Optional<AuthRefusal> refusal) {
    }
}
