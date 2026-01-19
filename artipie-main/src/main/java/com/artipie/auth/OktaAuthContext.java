/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.auth;

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
