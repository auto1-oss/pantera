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

import com.auto1.pantera.http.RsStatus;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Why a login was refused, when the code that refused it knows more than
 * "wrong credentials": the identity provider asked for a second factor,
 * the account is locked, logins for the user are delayed.
 *
 * <p>{@link Authentication#user(String, String)} answers with a user or
 * with nothing. The code inside the chain that knows the cause
 * {@link #note() notes} it for the thread, and the entry point that called
 * the chain {@link #take() takes} it when the call returns: see
 * {@link PasswordCheck}. This works because a chain answers on the thread
 * it was called on; code that hands a login to another thread has to carry
 * the refusal over itself.</p>
 *
 * <p>A refusal names the reason token of the access record
 * ({@code event.reason}), the status of the answer (401 unless stated), the
 * seconds a client should wait ({@code Retry-After}) and the text of the
 * answer.</p>
 *
 * @since 2.2.15
 */
public final class AuthRefusal {

    /**
     * Refusal noted for the login being checked on this thread.
     */
    private static final ThreadLocal<AuthRefusal> NOTED = new ThreadLocal<>();

    /**
     * Form of a reason token.
     */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]+");

    /**
     * Reason token.
     */
    private final String token;

    /**
     * Status of the answer.
     */
    private final RsStatus code;

    /**
     * Seconds to wait before the next attempt, 0 for none.
     */
    private final long wait;

    /**
     * Text of the answer, empty for none.
     */
    private final String text;

    /**
     * Refusal answered as a failed login (401).
     *
     * @param reason Reason token, lower-case {@code [a-z0-9_]}
     */
    public AuthRefusal(final String reason) {
        this(reason, RsStatus.UNAUTHORIZED, 0L, "");
    }

    /**
     * Ctor.
     *
     * @param reason Reason token, lower-case {@code [a-z0-9_]}
     * @param status Status of the answer
     * @param retry Seconds to wait before the next attempt, 0 for none
     * @param message Text of the answer, empty for none
     */
    public AuthRefusal(
        final String reason, final RsStatus status, final long retry, final String message
    ) {
        if (reason == null || !AuthRefusal.TOKEN.matcher(reason).matches()) {
            throw new IllegalArgumentException(
                "A refusal reason is a lower-case token of [a-z0-9_]"
            );
        }
        if (retry < 0L) {
            throw new IllegalArgumentException("A wait time cannot be negative");
        }
        this.token = reason;
        this.code = status;
        this.wait = retry;
        this.text = message == null ? "" : message;
    }

    /**
     * The refusal noted on this thread, which stays noted. For code inside
     * the chain: a wrapper that counts failed logins must not count a
     * login that a provider refused for a reason of its own.
     *
     * @return The refusal, if one was noted
     */
    public static Optional<AuthRefusal> noted() {
        return Optional.ofNullable(AuthRefusal.NOTED.get());
    }

    /**
     * Take the refusal noted on this thread, leaving none.
     *
     * @return The refusal, if one was noted
     */
    public static Optional<AuthRefusal> take() {
        final AuthRefusal noted = AuthRefusal.NOTED.get();
        AuthRefusal.NOTED.remove();
        return Optional.ofNullable(noted);
    }

    /**
     * Note this refusal for the login being checked on this thread. A later
     * note replaces an earlier one.
     */
    public void note() {
        AuthRefusal.NOTED.set(this);
    }

    /**
     * Reason token for the access record.
     *
     * @return Token
     */
    public String reason() {
        return this.token;
    }

    /**
     * Status of the answer.
     *
     * @return Status
     */
    public RsStatus status() {
        return this.code;
    }

    /**
     * Seconds a client should wait before the next attempt.
     *
     * @return Seconds, empty when there is no wait
     */
    public OptionalLong retryAfter() {
        final OptionalLong res;
        if (this.wait > 0L) {
            res = OptionalLong.of(this.wait);
        } else {
            res = OptionalLong.empty();
        }
        return res;
    }

    /**
     * Text of the answer.
     *
     * @return Text, empty for none
     */
    public String message() {
        return this.text;
    }

    @Override
    public String toString() {
        return String.format(
            "%s(%s, %s)", this.getClass().getSimpleName(), this.token, this.code
        );
    }
}
