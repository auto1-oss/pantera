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
package com.auto1.pantera.auth;

/**
 * Thread-local context of the sign-in being checked, for the Okta password
 * provider: the second-factor code a person typed, and whether a person is
 * there at all.
 *
 * <p>Only an entry point where a person signs in (the sign-in form and the
 * token API behind it) marks the thread {@link #setInteractive(boolean)
 * interactive}. A repository request never does, so the provider knows
 * that nobody can answer a push or type a code.</p>
 */
public final class OktaAuthContext {

    private static final ThreadLocal<String> MFA_CODE = new ThreadLocal<>();

    /**
     * Whether a person is signing in on this thread.
     */
    private static final ThreadLocal<Boolean> INTERACTIVE = new ThreadLocal<>();

    private OktaAuthContext() {
    }

    public static void setMfaCode(final String code) {
        if (code == null || code.isEmpty()) {
            MFA_CODE.remove();
        } else {
            MFA_CODE.set(code);
        }
    }

    /**
     * Mark the sign-in on this thread as one a person makes.
     *
     * @param interactive True when a person can answer a second factor
     */
    public static void setInteractive(final boolean interactive) {
        if (interactive) {
            INTERACTIVE.set(Boolean.TRUE);
        } else {
            INTERACTIVE.remove();
        }
    }

    public static void clear() {
        MFA_CODE.remove();
        INTERACTIVE.remove();
    }

    public static String mfaCode() {
        return MFA_CODE.get();
    }

    /**
     * Whether a person is signing in on this thread.
     *
     * @return False for a repository login, the default
     */
    public static boolean interactive() {
        return Boolean.TRUE.equals(INTERACTIVE.get());
    }
}
