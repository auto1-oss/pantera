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

/**
 * Logging context - DEPRECATED.
 * Logging is now configured via log4j2.xml instead of YAML.
 * This class is kept for backward compatibility only.
 *
 * @since 0.28.0
 * @deprecated Use log4j2.xml for logging configuration
 */
@Deprecated
public final class LoggingContext {

    /**
     * Constructor.
     * @param meta Meta section from Pantera YAML settings (ignored)
     */
    public LoggingContext(final YamlMapping meta) {
        // No-op: logging is now configured via log4j2.xml
    }

    /**
     * Check if logging configuration is present.
     * @return Always false (logging via log4j2.xml now)
     * @deprecated Use log4j2.xml
     */
    @Deprecated
    public boolean hasConfiguration() {
        return false;
    }

    /**
     * Check if logging configuration is configured.
     * @return Always false (logging via log4j2.xml now)
     * @deprecated Use log4j2.xml
     */
    @Deprecated
    public boolean configured() {
        return false;
    }

    /**
     * Apply the logging configuration.
     * No-op: logging is configured via log4j2.xml.
     * @deprecated Use log4j2.xml
     */
    @Deprecated
    public void apply() {
        // No-op: logging is now configured via log4j2.xml
    }
}

