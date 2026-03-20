/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.misc;

import com.auto1.pantera.asto.PanteraIOException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Pantera properties.
 * @since 0.21
 */
@SuppressWarnings("PMD.ConstructorOnlyInitializesOrCallOtherConstructors")
public final class PanteraProperties {
    /**
     * Key of field which contains Pantera version.
     */
    public static final String VERSION_KEY = "pantera.version";

    /**
     * Expiration time for cached auth.
     */
    public static final String AUTH_TIMEOUT = "pantera.cached.auth.timeout";

    /**
     * Expiration time for cache of storage setting.
     */
    public static final String STORAGE_TIMEOUT = "pantera.storage.file.cache.timeout";

    /**
     * Expiration time for cache of configuration files.
     */
    public static final String CONFIG_TIMEOUT = "pantera.config.cache.timeout";

    /**
     * Expiration time for cache of configuration files.
     */
    public static final String SCRIPTS_TIMEOUT = "pantera.scripts.cache.timeout";

    /**
     * Expiration time for cache of credential setting.
     */
    public static final String CREDS_TIMEOUT = "pantera.credentials.file.cache.timeout";

    /**
     * Expiration time for cached filters.
     */
    public static final String FILTERS_TIMEOUT = "pantera.cached.filters.timeout";

    /**
     * Name of file with properties.
     */
    private final String filename;

    /**
     * Properties.
     */
    private final Properties properties;

    /**
     * Ctor with default name of file with properties.
     */
    public PanteraProperties() {
        this("pantera.properties");
    }

    /**
     * Ctor.
     * @param filename Filename with properties
     */
    public PanteraProperties(final String filename) {
        this.filename = filename;
        this.properties = new Properties();
        this.loadProperties();
    }

    /**
     * Obtains version of Pantera.
     * @return Version
     */
    public String version() {
        return this.properties.getProperty(PanteraProperties.VERSION_KEY);
    }

    /**
     * Obtains a value by specified key from properties file.
     * @param key Key for obtaining value
     * @return A value by specified key from properties file.
     */
    public Optional<String> valueBy(final String key) {
        return Optional.ofNullable(
            this.properties.getProperty(key)
        );
    }

    /**
     * Load content of file.
     */
    private void loadProperties() {
        try (InputStream stream = Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream(this.filename)) {
            if (stream != null) {
                this.properties.load(stream);
            }
        } catch (final IOException exc) {
            throw new PanteraIOException(exc);
        }
    }
}
