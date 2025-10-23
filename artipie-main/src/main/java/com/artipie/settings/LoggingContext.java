/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings;

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
     * @param meta Meta section from Artipie YAML settings (ignored)
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
