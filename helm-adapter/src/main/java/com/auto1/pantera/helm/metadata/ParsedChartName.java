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
package com.auto1.pantera.helm.metadata;

/**
 * Encapsulates parsed chart name for validation.
 * @since 0.3
 */
public final class ParsedChartName {
    /**
     * Entries.
     */
    private static final String ENTRS = "entries:";

    /**
     * Chart name.
     */
    private final String name;

    /**
     * Ctor.
     * @param name Parsed from file with breaks chart name
     */
    public ParsedChartName(final String name) {
        this.name = name;
    }

    /**
     * Validates chart name.
     * @return True if parsed chart name is valid, false otherwise.
     */
    public boolean valid() {
        final String trimmed = this.name.trim();
        return trimmed.endsWith(":")
            && !ParsedChartName.ENTRS.equals(trimmed)
            && trimmed.charAt(0) != '-';
    }
}
