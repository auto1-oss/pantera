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
 * JWT token types. Stored as the "type" claim in every token.
 */
public enum TokenType {
    ACCESS("access"),
    REFRESH("refresh"),
    API("api");

    private final String value;

    TokenType(final String value) {
        this.value = value;
    }

    public String value() {
        return this.value;
    }

    /**
     * Parse from claim string. Returns null if unknown.
     */
    public static TokenType fromClaim(final String claim) {
        if (claim == null) {
            return null;
        }
        for (final TokenType type : values()) {
            if (type.value.equals(claim)) {
                return type;
            }
        }
        return null;
    }
}
