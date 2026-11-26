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

import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps the failed logins of user names.
 *
 * <p>A name is counted in lower case and without surrounding spaces: a
 * directory treats those spellings as one account, and a count per spelling
 * would give an attacker a new allowance with each. A missing name, and
 * one longer than a user name can be, is not stored.</p>
 *
 * <p>A store tells its own time ({@link Seen#now()}): the rules are applied
 * with the clock of the place the counts live in, so nodes with different
 * clocks agree.</p>
 *
 * @since 2.2.15
 */
public interface FailedLoginStore {

    /**
     * Longest name that is stored.
     */
    int MAX_NAME = 255;

    /**
     * Most names {@link #counted()} returns.
     */
    int MAX_COUNTED = 10_000;

    /**
     * State of a name.
     *
     * @param name User name
     * @return The state, {@link LoginState#NONE} for an unknown name, with
     *  the store's time
     */
    Seen read(String name);

    /**
     * Count one failed login. Concurrent failures of one name are each
     * counted.
     *
     * @param name User name
     * @param policy Policy the failure is counted under
     * @param origin Where the login came from
     * @return The state after the failure, with the store's time
     */
    Seen fail(String name, FailedLoginsConfig policy, LoginOrigin origin);

    /**
     * Forget the failures and the lock of a name.
     *
     * @param name User name
     * @return The state that was there
     */
    LoginState forget(String name);

    /**
     * What is kept for a name: its count, its lock and where its last
     * failed login came from.
     *
     * @param name User name
     * @return The entry, empty for a name without failures
     */
    Optional<LockedName> entry(String name);

    /**
     * Names with a recorded lock, oldest lock first.
     *
     * @return Locked names
     */
    List<LockedName> locked();

    /**
     * Forget the failures and the lock of every locked name.
     *
     * @return The names that were locked
     */
    List<String> unlockAll();

    /**
     * Names that have failed logins on record, locked or not: the most
     * recent ones, at most {@link #MAX_COUNTED}.
     *
     * @return Names in lower case, most recent failure first
     */
    List<String> counted();

    /**
     * A state with the time of the store that holds it.
     *
     * @param state State of the name
     * @param now The store's current time
     * @since 2.2.15
     */
    record Seen(LoginState state, Instant now) {
    }

    /**
     * The spelling a name is counted under.
     *
     * @since 2.2.15
     */
    final class Counted {

        /**
         * User name as it was sent.
         */
        private final String raw;

        /**
         * Ctor.
         *
         * @param name User name as it was sent
         */
        public Counted(final String name) {
            this.raw = name;
        }

        /**
         * The counted spelling.
         *
         * @return Lower-case name without surrounding spaces, empty when
         *  the name is missing or too long to be a user
         */
        public Optional<String> name() {
            Optional<String> res = Optional.empty();
            if (this.raw != null) {
                final String name = this.raw.strip().toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && name.length() <= FailedLoginStore.MAX_NAME) {
                    res = Optional.of(name);
                }
            }
            return res;
        }
    }
}
