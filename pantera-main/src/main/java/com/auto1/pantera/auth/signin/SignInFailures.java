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

import com.auto1.pantera.auth.failedlogins.FailedLoginStore;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.metrics.MicrometerMetrics;
import com.auto1.pantera.settings.policy.SignInReportConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The sign-in failures report: notes who fails to sign in, with what and
 * from where, and lists it.
 *
 * <p>An event is noted in memory and nothing else happens on the request
 * path: no database, no log record. {@link #flush()} adds what was noted
 * to the store, one row per user, client address and key, however many
 * requests it stands for. It is called once a minute, so a client that
 * repeats a failing request writes one row a minute. The memory is
 * bounded: when {@link #MAX_PENDING} lines wait, events of new lines are
 * counted as dropped and not kept.</p>
 *
 * @since 2.2.15
 */
public final class SignInFailures {

    /**
     * Most lines that wait in memory for the next flush.
     */
    static final int MAX_PENDING = 5_000;

    /**
     * Logger name.
     */
    private static final String LOG = "com.auto1.pantera.auth";

    /**
     * How often old lines are removed.
     */
    private static final Duration PURGE_EVERY = Duration.ofHours(1);

    /**
     * Where the lines are kept.
     */
    private final SignInFailureStore store;

    /**
     * Policy in force.
     */
    private final Supplier<SignInReportConfig> config;

    /**
     * Time.
     */
    private final Supplier<Instant> clock;

    /**
     * Lines that wait for the next flush.
     */
    private final Map<String, SignInFailure> pending;

    /**
     * Events not kept because the memory was full, since the last flush.
     */
    private final AtomicLong lost;

    /**
     * Events not kept because the memory was full, in all.
     */
    private final AtomicLong droppedTotal;

    /**
     * When old lines were last removed.
     */
    private volatile Instant purged;

    /**
     * Says what is known of a rejected key; null until the keys of the
     * installation are loaded.
     */
    private volatile KeyInspector inspector;

    /**
     * Ctor.
     *
     * @param store Where the lines are kept
     * @param config Policy in force, read on every event
     */
    public SignInFailures(
        final SignInFailureStore store, final Supplier<SignInReportConfig> config
    ) {
        this(store, config, Instant::now);
    }

    /**
     * Ctor.
     *
     * @param store Where the lines are kept
     * @param config Policy in force, read on every event
     * @param clock Time
     */
    SignInFailures(
        final SignInFailureStore store, final Supplier<SignInReportConfig> config,
        final Supplier<Instant> clock
    ) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.pending = new ConcurrentHashMap<>();
        this.lost = new AtomicLong();
        this.droppedTotal = new AtomicLong();
        this.purged = Instant.EPOCH;
    }

    /**
     * Use an inspector to say what is known of a rejected key.
     *
     * @param with The inspector, built with the public key of the
     *  installation
     */
    public void inspectKeysWith(final KeyInspector with) {
        this.inspector = with;
    }

    /**
     * A credential with the form of an API key was rejected: find out
     * what is known of it and note it.
     *
     * @param token The credential
     * @param sent User name it was sent with, empty when there was none
     * @param source Where the request came from
     * @return What is known of the key, for the answer; empty when it is
     *  no API key or nothing can inspect it
     */
    public Optional<RejectedKey> rejected(
        final String token, final String sent, final LoginSource source
    ) {
        final KeyInspector with = this.inspector;
        final Optional<RejectedKey> key;
        if (with == null) {
            key = Optional.empty();
        } else {
            key = with.inspect(token, sent);
        }
        key.ifPresent(known -> this.rejectedKey(known, source));
        return key;
    }

    /**
     * An API key was rejected.
     *
     * @param key What is known of the key
     * @param source Where the request came from
     */
    public void rejectedKey(final RejectedKey key, final LoginSource source) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordApiKeyRejected(key.reason());
        }
        this.note(
            SignInFailure.API_KEY, key.owner(), source, key.tokenId(), key.reason(),
            key.verified(), 1L, 0L, 0, null, ""
        );
    }

    /**
     * A password login failed and was counted.
     *
     * @param name User name
     * @param source Where the login came from
     */
    public void failedPassword(final String name, final LoginSource source) {
        this.note(
            SignInFailure.PASSWORD, name, source, "", "wrong_credentials", true, 1L, 0L, 0,
            null, ""
        );
    }

    /**
     * A password login was refused without a check, during a delay or a
     * lock.
     *
     * @param name User name
     * @param source Where the login came from
     * @param reason Why: {@code login_delayed} or {@code account_locked}
     */
    public void refusedPassword(final String name, final LoginSource source, final String reason) {
        this.note(SignInFailure.PASSWORD, name, source, "", reason, true, 0L, 1L, 0, null, "");
    }

    /**
     * A sign-in whose password was accepted but whose second factor was not
     * confirmed: a push denied or not answered, a wrong code.
     *
     * @param name User name
     * @param source Where the sign-in came from
     * @param counted Unconfirmed second factors counted toward the pause
     * @param paused End of the pause, null when the count is below the limit
     */
    public void unconfirmedSecondFactor(
        final String name, final LoginSource source, final int counted, final Instant paused
    ) {
        this.note(
            SignInFailure.SECOND_FACTOR, name, source, "", AuthRefusal.SECOND_FACTOR_FAILED,
            true, 1L, 0L, counted, paused, ""
        );
    }

    /**
     * A password sign-in was refused without a check during the pause after
     * unconfirmed second factors.
     *
     * @param name User name
     * @param source Where the sign-in came from
     * @param counted Unconfirmed second factors counted toward the pause
     * @param paused End of the pause
     */
    public void refusedPaused(
        final String name, final LoginSource source, final int counted, final Instant paused
    ) {
        this.note(
            SignInFailure.SECOND_FACTOR, name, source, "", "sign_in_paused", true, 0L, 1L,
            counted, paused, ""
        );
    }

    /**
     * An administrator unlocked a user.
     *
     * @param name User name
     * @param actor Who unlocked
     */
    public void unlocked(final String name, final String actor) {
        this.note(
            SignInFailure.UNLOCK, name, LoginSource.UNKNOWN, "", "unlocked", true, 1L, 0L, 0,
            null, actor
        );
    }

    /**
     * Lines of the report. What this node noted since its last flush is
     * written first, so the list is current for the node that is asked.
     *
     * @param query What to list
     * @return The page
     */
    public SignInFailureStore.Page list(final SignInFailureStore.Query query) {
        this.flush();
        return this.store.list(query);
    }

    /**
     * Events not kept because the memory was full.
     *
     * @return Count since the start
     */
    public long dropped() {
        return this.droppedTotal.get();
    }

    /**
     * Write what was noted to the store, and remove lines past their
     * retention once an hour. An event that cannot be written stays noted
     * for the next flush.
     */
    public synchronized void flush() {
        final List<SignInFailure> batch = new ArrayList<>(this.pending.size());
        for (final String line : new ArrayList<>(this.pending.keySet())) {
            final SignInFailure taken = this.pending.remove(line);
            if (taken != null) {
                batch.add(taken);
            }
        }
        final long missed = this.lost.getAndSet(0L);
        try {
            if (!batch.isEmpty()) {
                this.store.save(batch);
            }
            this.purge();
        } catch (final RuntimeException err) {
            for (final SignInFailure back : batch) {
                this.pending.merge(back.line(), back, SignInFailure::plus);
            }
            EcsLogger.warn(SignInFailures.LOG)
                .message(
                    "Sign-in failures could not be written; " + batch.size()
                        + " lines stay in memory for the next attempt"
                )
                .eventCategory("database")
                .eventAction("sign_in_report_flush")
                .eventOutcome("failure")
                .error(err)
                .field("log.source", "application")
                .log();
        }
        if (missed > 0) {
            EcsLogger.warn(SignInFailures.LOG)
                .message(
                    missed + " sign-in failures were not noted: more than "
                        + SignInFailures.MAX_PENDING + " users and addresses failed within a minute"
                )
                .eventCategory("authentication")
                .eventAction("sign_in_report_overflow")
                .eventOutcome("failure")
                .field("log.source", "application")
                .log();
        }
    }

    private void purge() {
        final Instant now = this.clock.get();
        if (Duration.between(this.purged, now).compareTo(SignInFailures.PURGE_EVERY) >= 0) {
            this.purged = now;
            final int removed = this.store.purge(
                now.minus(Duration.ofDays(this.config.get().retentionDays()))
            );
            if (removed > 0) {
                EcsLogger.info(SignInFailures.LOG)
                    .message(
                        removed + " lines of the sign-in failures report removed: older than "
                            + this.config.get().retentionDays() + " days"
                    )
                    .eventCategory("database")
                    .eventAction("sign_in_report_purge")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            }
        }
    }

    private void note(
        final String kind, final String name, final LoginSource source, final String token,
        final String reason, final boolean verified, final long hits, final long refused,
        final int failures, final Instant until, final String actor
    ) {
        if (!this.config.get().enabled()) {
            return;
        }
        // A name is noted as it is counted: lower case, no surrounding
        // spaces. One that is too long to be a user is not noted; a
        // missing one is, as "nobody".
        final Optional<String> counted;
        if (name == null || name.isBlank()) {
            counted = Optional.of("");
        } else {
            counted = new FailedLoginStore.Counted(name).name();
        }
        if (counted.isEmpty()) {
            return;
        }
        final Instant now = this.clock.get();
        final SignInFailure event = new SignInFailure(
            kind, counted.get(), source.clientIp(), token, reason, verified, source.userAgent(),
            source.path(), hits, refused, now, now, failures, until, actor
        );
        final boolean[] kept = {true};
        this.pending.compute(
            event.line(),
            (line, before) -> {
                final SignInFailure res;
                if (before != null) {
                    res = before.plus(event);
                } else if (this.pending.size() >= SignInFailures.MAX_PENDING) {
                    kept[0] = false;
                    res = null;
                } else {
                    res = event;
                }
                return res;
            }
        );
        if (!kept[0]) {
            this.lost.incrementAndGet();
            this.droppedTotal.incrementAndGet();
        }
    }
}
