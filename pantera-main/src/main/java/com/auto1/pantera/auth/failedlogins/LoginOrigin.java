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
package com.auto1.pantera.auth.failedlogins;

/**
 * Where a login came from: kept with the last failure of a user name, so
 * an admin can see which machine and which tool keeps failing.
 *
 * @param clientIp Client address, empty when unknown
 * @param userAgent Client program, empty when unknown
 * @param path Sign-in path: {@code ui}, {@code api} or {@code repository}
 * @param credential What was sent: {@code password} or {@code api_key}
 * @since 2.2.15
 */
public record LoginOrigin(String clientIp, String userAgent, String path, String credential) {

    /**
     * Longest client address kept.
     */
    public static final int MAX_IP = 64;

    /**
     * Longest client program kept.
     */
    public static final int MAX_AGENT = 512;

    /**
     * Longest path or credential token kept.
     */
    public static final int MAX_TOKEN = 16;

    /**
     * An origin nothing is known about.
     */
    public static final LoginOrigin UNKNOWN = new LoginOrigin("", "", "", "");

    /**
     * Ctor. Missing values become empty, long ones are cut: what a client
     * sends decides neither whether a failure is counted nor whether the
     * count can be stored.
     */
    public LoginOrigin {
        clientIp = LoginOrigin.cut(clientIp, LoginOrigin.MAX_IP); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        userAgent = LoginOrigin.cut(userAgent, LoginOrigin.MAX_AGENT); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        path = LoginOrigin.cut(path, LoginOrigin.MAX_TOKEN); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        credential = LoginOrigin.cut(credential, LoginOrigin.MAX_TOKEN); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
    }

    private static String cut(final String value, final int max) {
        final String res;
        if (value == null) {
            res = "";
        } else if (value.length() > max) {
            res = value.substring(0, max);
        } else {
            res = value;
        }
        return res;
    }
}
