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

import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.metrics.MicrometerMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Password providers behind the login delay and the account lock.
 *
 * <p>Before a provider is asked, the name is admitted: a login for a
 * locked name is refused with 403, one during a delay with 429 and
 * {@code Retry-After}. Neither reaches a provider, so neither becomes a
 * failed login at an identity provider, and neither is counted. A login
 * the providers reject is counted, and the one that reaches the lock limit
 * is answered as locked. A success forgets the failures.</p>
 *
 * <p>Not counted: a login a provider refused for a reason of its own (it
 * noted an {@link AuthRefusal}: a second factor was asked for, the
 * identity provider cannot be reached). Such a login is no wrong
 * password.</p>
 *
 * <p>Logins of one name are checked one at a time on a node. Guesses sent
 * at once can then reach the providers no more often than the lock limit
 * allows; without this, all of them would pass before the first failure is
 * counted. Logins that wait behind one with the same password take its
 * answer when it was accepted, so a build that opens many connections with
 * one right password asks the provider once.</p>
 *
 * @since 2.2.15
 */
public final class GuardedPasswords implements Authentication {

    /**
     * Number of locks the names are spread over.
     */
    private static final int STRIPES = 256;

    /**
     * Longest wait for the login of the same name that is being checked.
     */
    private static final long WAIT_SECONDS = 30L;

    /**
     * Password providers.
     */
    private final Authentication origin;

    /**
     * Failed logins.
     */
    private final FailedLogins logins;

    /**
     * Locks, one per stripe of names.
     */
    private final ReentrantLock[] stripes;

    /**
     * Logins accepted moments ago, by counted name.
     */
    private final Cache<String, Accepted> accepted;

    /**
     * Ctor.
     *
     * @param origin Password providers
     * @param logins Failed logins
     */
    public GuardedPasswords(final Authentication origin, final FailedLogins logins) {
        this.origin = origin;
        this.logins = logins;
        this.stripes = new ReentrantLock[GuardedPasswords.STRIPES];
        for (int idx = 0; idx < this.stripes.length; idx += 1) {
            this.stripes[idx] = new ReentrantLock();
        }
        this.accepted = Caffeine.newBuilder()
            .maximumSize(10_000L)
            .expireAfterWrite(Duration.ofSeconds(10L))
            .build();
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        final Optional<String> counted = new FailedLoginStore.Counted(username).name();
        final Optional<AuthUser> res;
        if (counted.isEmpty()) {
            // No such user can exist: nothing to count, nothing to lock.
            res = this.origin.user(username, password);
        } else {
            res = this.oneAtATime(counted.get(), username, password);
        }
        return res;
    }

    @Override
    public String toString() {
        return String.format("%s(%s)", this.getClass().getSimpleName(), this.origin);
    }

    private Optional<AuthUser> oneAtATime(
        final String counted, final String username, final String password
    ) {
        final ReentrantLock lock =
            this.stripes[(counted.hashCode() & Integer.MAX_VALUE) % this.stripes.length];
        boolean held = false;
        try {
            held = lock.tryLock(GuardedPasswords.WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        final Optional<AuthUser> res;
        if (held) {
            try {
                res = this.checked(counted, username, password);
            } finally {
                lock.unlock();
            }
        } else {
            new AuthRefusal(
                "sign_in_busy", RsStatus.SERVICE_UNAVAILABLE, 5L,
                "Sign-in is busy. Try again in a few seconds."
            ).note();
            if (MicrometerMetrics.isInitialized()) {
                MicrometerMetrics.getInstance().recordLoginRefused("sign_in_busy");
            }
            res = Optional.empty();
        }
        return res;
    }

    private Optional<AuthUser> checked(
        final String counted, final String username, final String password
    ) {
        final byte[] digest = GuardedPasswords.digest(counted, password);
        final Accepted before = this.accepted.getIfPresent(counted);
        Optional<AuthUser> res = Optional.empty();
        if (before != null && MessageDigest.isEqual(before.digest(), digest)) {
            res = Optional.of(before.user());
        } else {
            final Admission admission = this.logins.admission(username);
            if (admission.kind() == Admission.Kind.OPEN) {
                res = this.asked(counted, username, password, digest);
            } else {
                this.logins.refuse(admission, username);
            }
        }
        return res;
    }

    private Optional<AuthUser> asked(
        final String counted, final String username, final String password, final byte[] digest
    ) {
        final Optional<AuthUser> res = this.origin.user(username, password);
        if (res.isPresent()) {
            this.logins.succeeded(username);
            this.accepted.put(counted, new Accepted(digest, res.get()));
        } else if (AuthRefusal.noted().isEmpty()) {
            // A failure changes what the name may do: an answer accepted
            // moments ago is not handed to the logins that follow it.
            this.accepted.invalidate(counted);
            final LoginSource source = LoginSource.current();
            final Admission after = this.logins.failed(
                username,
                new LoginOrigin(source.clientIp(), source.userAgent(), source.path(), "password")
            );
            if (after.kind() == Admission.Kind.LOCKED) {
                this.logins.refuse(after);
            }
        }
        return res;
    }

    private static byte[] digest(final String counted, final String password) {
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(counted.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            // A provider may take passwords on the sign-in form only: an
            // answer given to the form is not one for a repository request.
            if (LoginSource.current().form()) {
                sha.update((byte) 1);
            } else {
                sha.update((byte) 2);
            }
            if (password != null) {
                sha.update(password.getBytes(StandardCharsets.UTF_8));
            }
            return sha.digest();
        } catch (final NoSuchAlgorithmException err) {
            throw new IllegalStateException("SHA-256 is not available", err);
        }
    }

    /**
     * A login that was accepted.
     *
     * @param digest Digest of the name and the password
     * @param user The user it signed in
     * @since 2.2.15
     */
    private record Accepted(byte[] digest, AuthUser user) {
    }
}
