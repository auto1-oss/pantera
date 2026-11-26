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

/**
 * Where the login being checked on this thread comes from: the client
 * address, the client program and the sign-in path.
 *
 * <p>{@link PasswordCheck} binds it for the time of one call of the
 * sign-in chain, so code inside the chain that records a failed login can
 * say which machine and which tool keeps failing.</p>
 *
 * @param clientIp Client address, empty when unknown
 * @param userAgent Client program, empty when unknown
 * @param path Sign-in path: {@code repository}, {@code ui} or {@code api}
 * @since 2.2.15
 */
public record LoginSource(String clientIp, String userAgent, String path) {

    /**
     * Path of a login that comes with a repository request.
     */
    public static final String REPOSITORY = "repository";

    /**
     * A source nothing is known about.
     */
    public static final LoginSource UNKNOWN = new LoginSource("", "", "");

    /**
     * Source of the login being checked on this thread.
     */
    private static final ThreadLocal<LoginSource> CURRENT = new ThreadLocal<>();

    /**
     * Ctor. Missing values become empty.
     */
    public LoginSource {
        clientIp = clientIp == null ? "" : clientIp; // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        userAgent = userAgent == null ? "" : userAgent; // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        path = path == null ? "" : path; // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
    }

    /**
     * Source of the login being checked on this thread.
     *
     * @return The source, {@link #UNKNOWN} outside a check
     */
    public static LoginSource current() {
        final LoginSource bound = LoginSource.CURRENT.get();
        final LoginSource res;
        if (bound == null) {
            res = LoginSource.UNKNOWN;
        } else {
            res = bound;
        }
        return res;
    }

    /**
     * Bind this source to the thread.
     */
    void bind() {
        LoginSource.CURRENT.set(this);
    }

    /**
     * Take the source off the thread.
     */
    static void unbind() {
        LoginSource.CURRENT.remove();
    }
}
