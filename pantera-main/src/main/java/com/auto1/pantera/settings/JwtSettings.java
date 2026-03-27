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
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.Optional;

/**
 * JWT token settings.
 * @since 1.20.7
 */
public final class JwtSettings {

    /**
     * Default expiry in seconds (24 hours).
     */
    public static final int DEFAULT_EXPIRY_SECONDS = 86400;

    /**
     * Whether tokens expire.
     */
    private final boolean expires;

    /**
     * Expiry time in seconds (only used if expires is true).
     */
    private final int expirySeconds;

    /**
     * JWT secret key.
     */
    private final String secret;

    /**
     * Ctor with defaults (permanent tokens).
     */
    public JwtSettings() {
        this(false, DEFAULT_EXPIRY_SECONDS, "some secret");
    }

    /**
     * Ctor.
     * @param expires Whether tokens expire
     * @param expirySeconds Expiry time in seconds
     * @param secret JWT secret key
     */
    public JwtSettings(final boolean expires, final int expirySeconds, final String secret) {
        this.expires = expires;
        this.expirySeconds = expirySeconds;
        this.secret = secret;
    }

    /**
     * Whether tokens should expire.
     * @return True if tokens expire
     */
    public boolean expires() {
        return this.expires;
    }

    /**
     * Token expiry time in seconds.
     * @return Expiry seconds
     */
    public int expirySeconds() {
        return this.expirySeconds;
    }

    /**
     * JWT secret key for signing.
     * @return Secret key
     */
    public String secret() {
        return this.secret;
    }

    /**
     * Optional expiry in seconds (empty if permanent).
     * @return Optional expiry
     */
    public Optional<Integer> optionalExpiry() {
        if (this.expires) {
            return Optional.of(this.expirySeconds);
        }
        return Optional.empty();
    }

    /**
     * Parse JWT settings from YAML.
     * @param meta Meta YAML mapping
     * @return JWT settings
     */
    public static JwtSettings fromYaml(final YamlMapping meta) {
        if (meta == null) {
            return new JwtSettings();
        }
        final YamlMapping jwt = meta.yamlMapping("jwt");
        if (jwt == null) {
            return new JwtSettings();
        }
        final String expiresStr = jwt.string("expires");
        final boolean expires = expiresStr != null && Boolean.parseBoolean(expiresStr);
        int expirySeconds = DEFAULT_EXPIRY_SECONDS;
        final String expiryStr = jwt.string("expiry-seconds");
        if (expiryStr != null) {
            try {
                expirySeconds = Integer.parseInt(expiryStr.trim());
                if (expirySeconds <= 0) {
                    expirySeconds = DEFAULT_EXPIRY_SECONDS;
                }
            } catch (final NumberFormatException ex) {
                EcsLogger.warn("com.auto1.pantera.settings")
                    .message("Invalid JWT expiry-seconds value, using default")
                    .error(ex)
                    .log();
            }
        }
        String secret = jwt.string("secret");
        if (secret == null || secret.trim().isEmpty()) {
            secret = resolveEnv(jwt.string("secret"));
            if (secret == null || secret.trim().isEmpty()) {
                secret = "some secret";
            }
        } else {
            // Check for env var syntax
            secret = resolveEnv(secret);
        }
        return new JwtSettings(expires, expirySeconds, secret);
    }

    /**
     * Resolve environment variable if value starts with ${.
     * @param value Value to resolve
     * @return Resolved value
     */
    private static String resolveEnv(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            final String envName = trimmed.substring(2, trimmed.length() - 1);
            final String envVal = System.getenv(envName);
            return envVal != null ? envVal : trimmed;
        }
        return trimmed;
    }
}
