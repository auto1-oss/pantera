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
package com.auto1.pantera.settings.policy;

import com.auto1.pantera.http.auth.LoginSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where a password may be handed to an identity provider, per provider
 * type.
 *
 * <p>A provider such as Okta or Keycloak checks a user name and password
 * against the directory. That is wanted on the sign-in form, where a
 * person types the password and can answer a second factor. On repository
 * requests a build tool sends the stored password with every request, and
 * every wrong one becomes a failed login in the directory. The mode says
 * how far a provider's password check reaches:</p>
 * <ul>
 *   <li>{@code all} - the sign-in form and repository requests;</li>
 *   <li>{@code form} - the sign-in form and endpoint only;</li>
 *   <li>{@code off} - nowhere: the provider checks no password.</li>
 * </ul>
 *
 * <p>The sign-in button of a provider (the browser redirect) is not a
 * password check and is not affected.</p>
 *
 * @param modes Mode by provider type; a type that is not named is
 *  {@code all}
 * @since 2.2.15
 */
public record PasswordSignInConfig(Map<String, String> modes) {

    /**
     * Passwords are checked for the sign-in form and repository requests.
     */
    public static final String ALL = "all";

    /**
     * Passwords are checked for the sign-in form and endpoint only.
     */
    public static final String FORM = "form";

    /**
     * No password is checked.
     */
    public static final String OFF = "off";

    /**
     * Provider types that have a mode.
     */
    public static final List<String> TYPES = List.of("okta", "keycloak", "github");

    /**
     * The modes.
     */
    private static final List<String> MODES =
        List.of(PasswordSignInConfig.ALL, PasswordSignInConfig.FORM, PasswordSignInConfig.OFF);

    /**
     * Ctor.
     */
    public PasswordSignInConfig {
        final Map<String, String> checked = new TreeMap<>();
        for (final String type : PasswordSignInConfig.TYPES) {
            checked.put(type, PasswordSignInConfig.ALL);
        }
        for (final Map.Entry<String, String> entry : modes.entrySet()) {
            if (!PasswordSignInConfig.TYPES.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                    String.format(
                        "'%s' is not a provider with a password sign-in mode (%s)",
                        entry.getKey(), String.join(", ", PasswordSignInConfig.TYPES)
                    )
                );
            }
            checked.put(entry.getKey(), PasswordSignInConfig.checked(entry.getValue()));
        }
        modes = Map.copyOf(checked); // NOPMD UnusedAssignment - compact canonical constructor: the assignment sets the record component
    }

    /**
     * The defaults: every provider checks passwords everywhere.
     *
     * @return Policy
     */
    public static PasswordSignInConfig defaults() {
        return new PasswordSignInConfig(Map.of());
    }

    /**
     * Mode of a provider.
     *
     * @param type Provider type
     * @return The mode, {@code all} for a type without one
     */
    public String mode(final String type) {
        return this.modes.getOrDefault(type, PasswordSignInConfig.ALL);
    }

    /**
     * Whether a provider may check the password of a login.
     *
     * @param type Provider type
     * @param source Where the login comes from
     * @return True when the provider may be asked
     */
    public boolean allows(final String type, final LoginSource source) {
        final String mode = this.mode(type);
        return PasswordSignInConfig.ALL.equals(mode)
            || PasswordSignInConfig.FORM.equals(mode) && source.form();
    }

    /**
     * What a remembered login is bound to. A login accepted on the form
     * must not answer a repository request when a provider takes
     * passwords on the form only, and a login accepted under one policy
     * must not answer under another.
     *
     * @param source Where the login comes from
     * @return Scope, empty when no provider is limited
     */
    public String scope(final LoginSource source) {
        final StringBuilder scope = new StringBuilder(48);
        for (final String type : PasswordSignInConfig.TYPES) {
            if (!PasswordSignInConfig.ALL.equals(this.mode(type))) {
                scope.append(type).append('=').append(this.mode(type)).append(';');
            }
        }
        if (scope.length() > 0) {
            if (source.form()) {
                scope.append(PasswordSignInConfig.FORM);
            } else {
                scope.append(LoginSource.REPOSITORY);
            }
        }
        return scope.toString();
    }

    /**
     * A value as a mode.
     *
     * @param value Value of a setting
     * @return The mode
     * @throws IllegalArgumentException When the value is no mode
     */
    static String checked(final String value) {
        final String mode;
        if (value == null) {
            mode = "";
        } else {
            mode = value.trim().toLowerCase(Locale.ROOT);
        }
        if (!PasswordSignInConfig.MODES.contains(mode)) {
            throw new IllegalArgumentException(
                String.format(
                    "'%s' is not a password sign-in mode (%s)",
                    value, String.join(", ", PasswordSignInConfig.MODES)
                )
            );
        }
        return mode;
    }
}
