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

import java.util.Set;

/**
 * Server-side password complexity validation.
 *
 * <p>Pantera enforces these rules at the API boundary so that even a
 * direct DB-bypassing JSON POST cannot set a weak password. The same
 * rules are mirrored in the UI for live feedback, but the UI is not
 * trusted — every {@code alterPassword} call goes through
 * {@link #validate(String, String)}.</p>
 *
 * <p>Rules:
 * <ul>
 *   <li>Minimum {@value #MIN_LENGTH} characters</li>
 *   <li>At least one uppercase letter</li>
 *   <li>At least one lowercase letter</li>
 *   <li>At least one digit</li>
 *   <li>At least one special character from {@value #SPECIAL_CHARS}</li>
 *   <li>Not in the well-known weak-password set</li>
 *   <li>Not equal to the username (case-insensitive)</li>
 * </ul>
 *
 * @since 2.1.0
 */
public final class PasswordPolicy {

    /**
     * Minimum password length.
     */
    public static final int MIN_LENGTH = 12;

    /**
     * Allowed special characters (printable ASCII punctuation).
     */
    public static final String SPECIAL_CHARS = "!@#$%^&*()-_=+[]{};:,.<>?/|~`'\"\\";

    /**
     * Common weak passwords that must never be accepted, even if they
     * happen to satisfy the character-class rules. Lowercased.
     */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
        "password",
        "password123",
        "password1234",
        "admin",
        "administrator",
        "changeme",
        "welcome",
        "welcome123",
        "qwerty",
        "qwerty123",
        "letmein",
        "iloveyou",
        "trustno1",
        "dragon",
        "monkey",
        "abc123",
        "passw0rd",
        "p@ssw0rd",
        "p@ssword",
        "pantera",
        "pantera123"
    );

    private PasswordPolicy() {
    }

    /**
     * Validate a password against the policy. Returns null if the password
     * is acceptable, or a human-readable failure message if not. Callers
     * should map a non-null result to a 400 Bad Request response.
     *
     * @param username Account username (used for "not equal to username" rule)
     * @param password Plaintext password to validate
     * @return null if valid, error message otherwise
     */
    public static String validate(final String username, final String password) {
        if (password == null || password.isEmpty()) {
            return "Password is required";
        }
        if (password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters long";
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            return "Password must not match the username";
        }
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        for (int i = 0; i < password.length(); i++) {
            final char ch = password.charAt(i);
            if (Character.isUpperCase(ch)) {
                hasUpper = true;
            } else if (Character.isLowerCase(ch)) {
                hasLower = true;
            } else if (Character.isDigit(ch)) {
                hasDigit = true;
            } else if (SPECIAL_CHARS.indexOf(ch) >= 0) {
                hasSpecial = true;
            }
        }
        if (!hasUpper) {
            return "Password must contain at least one uppercase letter";
        }
        if (!hasLower) {
            return "Password must contain at least one lowercase letter";
        }
        if (!hasDigit) {
            return "Password must contain at least one digit";
        }
        if (!hasSpecial) {
            return "Password must contain at least one special character ("
                + SPECIAL_CHARS + ")";
        }
        if (WEAK_PASSWORDS.contains(password.toLowerCase(java.util.Locale.ROOT))) {
            return "Password is in the well-known weak-password list";
        }
        return null;
    }
}
