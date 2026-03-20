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
 * Thread-local context for Okta authentication extras (e.g. MFA code).
 */
public final class OktaAuthContext {

    private static final ThreadLocal<String> MFA_CODE = new ThreadLocal<>();

    private OktaAuthContext() {
    }

    public static void setMfaCode(final String code) {
        if (code == null || code.isEmpty()) {
            MFA_CODE.remove();
        } else {
            MFA_CODE.set(code);
        }
    }

    public static void clear() {
        MFA_CODE.remove();
    }

    public static String mfaCode() {
        return MFA_CODE.get();
    }
}
