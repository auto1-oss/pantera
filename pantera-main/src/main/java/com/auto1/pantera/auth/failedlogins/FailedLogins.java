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
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.metrics.MicrometerMetrics;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The failed logins of user names under the policy in force: whether a
 * login may be checked, what a failure and a success change, and who is
 * locked.
 *
 * <p>The policy is read on every decision, so a change in System Settings
 * applies to the next login. The counts live in a
 * {@link FailedLoginStore}; the rules live in {@link LoginState}.</p>
 *
 * <p>{@link #locked(String)} answers from a set of the locked names held
 * in memory, because every authenticated request asks it. The set changes
 * at once on the node that locks or unlocks and is loaded again by
 * {@link #refresh()}, which also picks up what other nodes and a manual
 * change of the table did.</p>
 *
 * @since 2.2.15
 */
public final class FailedLogins {

    /**
     * Logger name.
     */
    private static final String LOG = "com.auto1.pantera.auth";

    /**
     * Reason of a login refused because its user is locked.
     */
    private static final String LOCKED = "account_locked";

    /**
     * Where the counts live.
     */
    private final FailedLoginStore store;

    /**
     * Policy in force.
     */
    private final Supplier<FailedLoginsConfig> config;

    /**
     * Names whose lock is in force, as last loaded or changed here.
     */
    private volatile Set<String> lockedNow;

    /**
     * Ctor.
     *
     * @param store Where the counts live
     * @param config Policy in force, read on every decision
     */
    public FailedLogins(final FailedLoginStore store, final Supplier<FailedLoginsConfig> config) {
        this.store = store;
        this.config = config;
        this.lockedNow = Set.of();
    }

    /**
     * Policy in force.
     *
     * @return Policy
     */
    public FailedLoginsConfig policy() {
        return this.config.get();
    }

    /**
     * Failures counted for a name.
     *
     * @param name User name
     * @return State
     */
    public LoginState state(final String name) {
        return this.store.read(name).state();
    }

    /**
     * Whether a password login for a name may be checked now. A lock that
     * has ended by itself is removed here: the user starts with a clean
     * count.
     *
     * @param name User name
     * @return Admission
     */
    public Admission admission(final String name) {
        final FailedLoginStore.Seen seen = this.store.read(name);
        final FailedLoginsConfig policy = this.config.get();
        if (seen.state().lockExpired(seen.now(), policy)) {
            this.store.forget(name);
            this.mark(name, false);
            FailedLogins.unlocked("automatic", 1);
            EcsLogger.info(FailedLogins.LOG)
                .message(
                    "User unlocked automatically, " + policy.unlockAfterMinutes()
                        + " minutes after the lock"
                )
                .eventCategory("authentication")
                .eventAction("account_unlock")
                .eventOutcome("success")
                .field("user.name", name)
                .field("log.source", "application")
                .log();
        }
        return seen.state().admission(seen.now(), policy);
    }

    /**
     * Count a failed login.
     *
     * @param name User name
     * @param origin Where the login came from
     * @return What applies to the next login of the name
     */
    public Admission failed(final String name, final LoginOrigin origin) {
        final FailedLoginsConfig policy = this.config.get();
        final FailedLoginStore.Seen seen = this.store.fail(name, policy, origin);
        final LoginState state = seen.state();
        if (state.locked() && state.lockedAt().equals(state.lastFailure())) {
            this.mark(name, true);
            if (MicrometerMetrics.isInitialized()) {
                MicrometerMetrics.getInstance().recordAccountLock();
            }
            EcsLogger.warn(FailedLogins.LOG)
                .message(
                    "User locked after " + state.failures() + " failed logins (last from "
                        + origin.path() + ", " + origin.credential() + ")"
                )
                .eventCategory("authentication")
                .eventAction("account_lock")
                .eventOutcome("success")
                .field("event.reason", "account_locked")
                .field("user.name", name)
                .field("log.source", "application")
                .log();
        }
        return state.admission(seen.now(), policy);
    }

    /**
     * A password login succeeded: its failures are forgotten.
     *
     * @param name User name
     */
    public void succeeded(final String name) {
        if (this.store.read(name).state().failures() > 0) {
            this.store.forget(name);
            this.mark(name, false);
        }
    }

    /**
     * Whether a name is locked out of every credential: its lock is in
     * force and the policy lets a lock block API keys, sessions and an
     * identity provider's button too. Answers from memory.
     *
     * @param name User name
     * @return True when no credential of the name may be used
     */
    public boolean locked(final String name) {
        return this.config.get().lockAllCredentials() && this.passwordLocked(name);
    }

    /**
     * Whether the password sign-in of a name is locked: its lock is in
     * force, whatever the policy says about other credentials. Answers
     * from memory.
     *
     * @param name User name
     * @return True when the lock of the name is in force
     */
    public boolean passwordLocked(final String name) {
        return new FailedLoginStore.Counted(name).name()
            .map(this.lockedNow::contains)
            .orElse(false);
    }

    /**
     * Names whose lock is in force, oldest lock first.
     *
     * @return Locked names
     */
    public List<LockedName> lockedNames() {
        final FailedLoginsConfig policy = this.config.get();
        final Instant now = this.store.read("").now();
        final List<LockedName> res = new ArrayList<>(16);
        for (final LockedName one : this.store.locked()) {
            final LoginState state =
                new LoginState(one.failures(), one.lastFailure(), one.lockedAt());
            if (!state.lockExpired(now, policy)) {
                res.add(one);
            }
        }
        return res;
    }

    /**
     * What is kept for a name, for an admin's view of a user.
     *
     * @param name User name
     * @return Count, lock and origin of the last failure; empty for a name
     *  without failed logins
     */
    public Optional<LockedName> details(final String name) {
        return this.store.entry(name);
    }

    /**
     * Load the set of locked names again. With the lock switched off the
     * recorded locks are removed, so switching it on later brings no old
     * lock back.
     */
    public void refresh() {
        if (this.config.get().lockAfterFailures() == 0) {
            final List<String> released = this.store.unlockAll();
            if (!released.isEmpty()) {
                FailedLogins.unlocked("lock_off", released.size());
                EcsLogger.info(FailedLogins.LOG)
                    .message(
                        released.size() + " locked users unlocked: the account lock was"
                            + " switched off"
                    )
                    .eventCategory("authentication")
                    .eventAction("account_unlock")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            }
        }
        final Set<String> names = new HashSet<>(16);
        for (final LockedName one : this.lockedNames()) {
            names.add(one.name());
        }
        this.lockedNow = Set.copyOf(names);
    }

    /**
     * Remove the lock and the failures of a name.
     *
     * @param name User name
     * @param actor Who unlocks
     * @return True when the name was locked
     */
    public boolean unlock(final String name, final String actor) {
        final LoginState was = this.store.forget(name);
        this.mark(name, false);
        if (was.locked()) {
            FailedLogins.unlocked("admin", 1);
            EcsLogger.info(FailedLogins.LOG)
                .message("User unlocked by " + actor)
                .eventCategory("authentication")
                .eventAction("account_unlock")
                .eventOutcome("success")
                .field("user.name", name)
                .field("log.source", "application")
                .log();
        }
        return was.locked();
    }

    /**
     * Remove the lock and the failures of every locked name.
     *
     * @param actor Who unlocks
     * @return The names that were locked
     */
    public List<String> unlockAll(final String actor) {
        final List<String> names = this.store.unlockAll();
        this.lockedNow = Set.of();
        if (!names.isEmpty()) {
            FailedLogins.unlocked("admin", names.size());
            EcsLogger.info(FailedLogins.LOG)
                .message(names.size() + " locked users unlocked by " + actor)
                .eventCategory("authentication")
                .eventAction("account_unlock")
                .eventOutcome("success")
                .field("log.source", "application")
                .log();
        }
        return names;
    }

    /**
     * Refuse the login being checked on this thread without checking it:
     * note why for the answer and the access record, and count it.
     *
     * @param admission Locked or delayed
     */
    public void refuse(final Admission admission) {
        final AuthRefusal refusal;
        if (admission.kind() == Admission.Kind.LOCKED) {
            refusal = new AuthRefusal(
                FailedLogins.LOCKED, RsStatus.FORBIDDEN, 0L, "Account locked"
            );
        } else {
            refusal = new AuthRefusal(
                "login_delayed", RsStatus.TOO_MANY_REQUESTS, admission.seconds(),
                String.format(
                    "Too many failed logins. Try again in %d seconds.", admission.seconds()
                )
            );
        }
        refusal.note();
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordLoginRefused(refusal.reason());
        }
    }

    private static void unlocked(final String cause, final int count) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordAccountUnlock(cause, count);
        }
    }

    /**
     * Change the in-memory set for one name.
     *
     * @param name User name
     * @param locked Whether the name is locked now
     */
    private void mark(final String name, final boolean locked) {
        new FailedLoginStore.Counted(name).name().ifPresent(
            counted -> {
                synchronized (this) {
                    final Set<String> next = new HashSet<>(this.lockedNow);
                    final boolean changed;
                    if (locked) {
                        changed = next.add(counted);
                    } else {
                        changed = next.remove(counted);
                    }
                    if (changed) {
                        this.lockedNow = Set.copyOf(next);
                    }
                }
            }
        );
    }
}
