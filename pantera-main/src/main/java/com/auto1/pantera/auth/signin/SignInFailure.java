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
package com.auto1.pantera.auth.signin;

import java.time.Instant;
import java.util.Locale;

/**
 * One line of the sign-in failures report: what one user name did from
 * one client address, with one credential, counted over time.
 *
 * <p>The same record is the note of a single event (its counts are the
 * event's own) and the stored line (its counts are the sums).</p>
 *
 * @param kind What failed: {@link #API_KEY}, {@link #PASSWORD},
 *  {@link #FORM_LIMIT}, or {@link #UNLOCK} for an unlock by an admin
 * @param username User name in lower case; empty when none is known
 * @param clientIp Client address, empty when unknown
 * @param tokenId Id of the API key, empty for another kind or a key that
 *  could not be verified
 * @param reason Why: the reason token of the last event
 * @param verified For an API key: whether its signature proves the owner
 * @param userAgent Client program of the last event
 * @param path Sign-in path of the last event: {@code ui}, {@code api},
 *  {@code repository}
 * @param hits Rejected keys, failed logins or limit hits counted
 * @param refused Logins refused without a check, during a delay or a lock
 * @param firstSeen Time of the first event
 * @param lastSeen Time of the last event
 * @param failures For a limit hit: failures counted in the window
 * @param blockedUntil For a limit hit: when the block ends; null otherwise
 * @param actor For an unlock: who unlocked; empty otherwise
 * @since 2.2.15
 */
public record SignInFailure(
    String kind, String username, String clientIp, String tokenId, String reason,
    boolean verified, String userAgent, String path, long hits, long refused,
    Instant firstSeen, Instant lastSeen, int failures, Instant blockedUntil, String actor
) {

    /**
     * A rejected API key.
     */
    public static final String API_KEY = "api_key";

    /**
     * A failed password login.
     */
    public static final String PASSWORD = "password";

    /**
     * A login refused by the sign-in form's own limit.
     */
    public static final String FORM_LIMIT = "form_limit";

    /**
     * An unlock of a user by an administrator.
     */
    public static final String UNLOCK = "unlock";

    /**
     * Longest user name kept.
     */
    private static final int MAX_NAME = 255;

    /**
     * Longest client program text kept.
     */
    private static final int MAX_AGENT = 512;

    /**
     * Longest of the short texts kept.
     */
    private static final int MAX_SHORT = 64;

    /**
     * Ctor. Texts are cut to what the store holds; the name is lower case.
     */
    public SignInFailure {
        username = SignInFailure.cut(username, SignInFailure.MAX_NAME).strip().toLowerCase(Locale.ROOT); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        clientIp = SignInFailure.cut(clientIp, SignInFailure.MAX_SHORT); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        tokenId = SignInFailure.cut(tokenId, SignInFailure.MAX_SHORT); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        reason = SignInFailure.cut(reason, SignInFailure.MAX_SHORT); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        userAgent = SignInFailure.cut(userAgent, SignInFailure.MAX_AGENT); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        path = SignInFailure.cut(path, SignInFailure.MAX_SHORT); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
        actor = SignInFailure.cut(actor, SignInFailure.MAX_NAME); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
    }

    /**
     * What makes two events one line: the kind, the user name, the client
     * address and the key.
     *
     * @return Key of the line
     */
    public String line() {
        return String.join("\u0000", this.kind, this.username, this.clientIp, this.tokenId);
    }

    /**
     * This line with a later one added: the counts are summed, the texts
     * and the state are those of whichever is later.
     *
     * @param other Another event or line of the same {@link #line()}
     * @return The sum
     */
    public SignInFailure plus(final SignInFailure other) {
        final SignInFailure late;
        if (other.lastSeen.isBefore(this.lastSeen)) {
            late = this;
        } else {
            late = other;
        }
        final Instant first;
        if (other.firstSeen.isBefore(this.firstSeen)) {
            first = other.firstSeen;
        } else {
            first = this.firstSeen;
        }
        return new SignInFailure(
            this.kind, this.username, this.clientIp, this.tokenId, late.reason, late.verified,
            late.userAgent, late.path, this.hits + other.hits, this.refused + other.refused,
            first, late.lastSeen, late.failures, late.blockedUntil, late.actor
        );
    }

    private static String cut(final String text, final int max) {
        final String res;
        if (text == null) {
            res = "";
        } else if (text.length() > max) {
            res = text.substring(0, max);
        } else {
            res = text;
        }
        return res;
    }
}
