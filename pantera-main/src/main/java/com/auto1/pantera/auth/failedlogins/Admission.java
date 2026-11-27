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
 * Whether a password login for a user name may be checked now.
 *
 * @param kind Open, delayed, paused or locked
 * @param seconds Seconds to wait when delayed or paused, else 0
 * @since 2.2.15
 */
public record Admission(Kind kind, long seconds) {

    /**
     * The login may be checked.
     */
    public static final Admission OPEN = new Admission(Kind.OPEN, 0L);

    /**
     * The user is locked.
     */
    public static final Admission LOCKED = new Admission(Kind.LOCKED, 0L);

    /**
     * Logins for the user are delayed.
     *
     * @param seconds Seconds until the next login is checked, at least 1
     * @return Admission
     */
    public static Admission delayed(final long seconds) {
        return new Admission(Kind.DELAYED, Math.max(1L, seconds));
    }

    /**
     * Password sign-in of the user is paused after unconfirmed second
     * factors.
     *
     * @param seconds Seconds until the pause ends, at least 1
     * @return Admission
     */
    public static Admission paused(final long seconds) {
        return new Admission(Kind.PAUSED, Math.max(1L, seconds));
    }

    /**
     * Kind of admission.
     *
     * @since 2.2.15
     */
    public enum Kind {

        /**
         * The login may be checked.
         */
        OPEN,

        /**
         * Logins for the user are refused for a while after wrong passwords.
         */
        DELAYED,

        /**
         * Password sign-in of the user is refused for a while after second
         * factors that were not confirmed.
         */
        PAUSED,

        /**
         * The user is locked.
         */
        LOCKED
    }
}
