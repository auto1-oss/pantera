/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings;

import com.amihaiemil.eoyaml.YamlMapping;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.log4j.LogManager;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 * Logging context for managing global and per-logger logging levels.
 * Parses the `logging` section from Artipie YAML configuration and allows
 * dynamic configuration of logging levels.
 * 
 * Example YAML configuration:
 * <pre>
 * logging:
 *   level: INFO
 *   loggers:
 *     com.artipie: DEBUG
 *     com.artipie.maven.http.CachedProxySlice: TRACE
 * </pre>
 *
 * @since 0.28.0
 */
public final class LoggingContext {

    /**
     * Logging yaml section.
     */
    private static final String LOGGING = "logging";

    /**
     * Global logging level key.
     */
    private static final String LEVEL = "level";

    /**
     * Loggers configuration key.
     */
    private static final String LOGGERS = "loggers";

    /**
     * Default logging level.
     */
    private static final String DEFAULT_LEVEL = "INFO";

    /**
     * Global logging level.
     */
    private final String globalLevel;

    /**
     * Per-logger logging levels.
     */
    private final Map<String, String> loggerLevels;

    /**
     * Constructor.
     * @param meta Meta section from Artipie YAML settings
     */
    public LoggingContext(final YamlMapping meta) {
        final YamlMapping logging = meta.yamlMapping(LoggingContext.LOGGING);
        
        // Parse global level
        this.globalLevel = Optional.ofNullable(logging)
            .map(log -> log.string(LoggingContext.LEVEL))
            .orElse(LoggingContext.DEFAULT_LEVEL);

        // Parse per-logger levels
        this.loggerLevels = Optional.ofNullable(logging)
            .map(log -> log.yamlMapping(LoggingContext.LOGGERS))
            .map(loggers -> loggers.keys().stream()
                .collect(Collectors.toMap(
                    node -> node.asScalar().value(),
                    node -> loggers.string(node.asScalar().value())
                ))
            )
            .orElse(Collections.emptyMap());
    }

    /**
     * Get the global logging level.
     * @return Global logging level
     */
    public String globalLevel() {
        return this.globalLevel;
    }

    /**
     * Get per-logger logging levels.
     * @return Map of logger names to their levels
     */
    public Map<String, String> loggerLevels() {
        return Collections.unmodifiableMap(this.loggerLevels);
    }

    /**
     * Check if logging configuration is present.
     * @return True if logging section exists in configuration
     */
    public boolean hasConfiguration() {
        return !this.loggerLevels.isEmpty() || !LoggingContext.DEFAULT_LEVEL.equals(this.globalLevel);
    }

    /**
     * Check if logging configuration is configured (alias for hasConfiguration).
     * This method is used by VertxMain startup code.
     * @return True if logging section exists in configuration
     */
    public boolean configured() {
        return hasConfiguration();
    }

    /**
     * Apply the logging configuration to the logging system.
     * This method configures the logging levels for all configured loggers.
     */
    public void apply() {
        // Set global root logger level if different from default
        if (!LoggingContext.DEFAULT_LEVEL.equals(this.globalLevel)) {
            final Logger rootLogger = LogManager.getRootLogger();
            rootLogger.setLevel(Level.toLevel(this.globalLevel.toUpperCase()));
        }

        // Set individual logger levels
        this.loggerLevels.forEach((loggerName, level) -> {
            final Logger logger = LogManager.getLogger(loggerName);
            logger.setLevel(Level.toLevel(level.toUpperCase()));
        });
    }

    /**
     * Get the configured level for a specific logger.
     * @param loggerName Logger name
     * @return Optional level for the logger, empty if not specifically configured
     */
    public Optional<String> levelFor(final String loggerName) {
        return Optional.ofNullable(this.loggerLevels.get(loggerName));
    }
}
