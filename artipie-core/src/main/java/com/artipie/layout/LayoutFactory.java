/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.layout;

import java.util.Locale;

/**
 * Factory for creating storage layouts based on repository type.
 *
 * @since 1.0
 */
public final class LayoutFactory {

    /**
     * Repository type enum.
     */
    public enum RepositoryType {
        /**
         * Maven repository.
         */
        MAVEN,

        /**
         * Python (PyPI) repository.
         */
        PYPI,

        /**
         * Helm repository.
         */
        HELM,

        /**
         * File repository.
         */
        FILE,

        /**
         * NPM repository.
         */
        NPM,

        /**
         * Gradle repository.
         */
        GRADLE,

        /**
         * Composer repository.
         */
        COMPOSER
    }

    /**
     * Get storage layout for the given repository type.
     *
     * @param type Repository type
     * @return Storage layout instance
     */
    public static StorageLayout forType(final RepositoryType type) {
        final StorageLayout layout;
        switch (type) {
            case MAVEN:
                layout = new MavenLayout();
                break;
            case PYPI:
                layout = new PypiLayout();
                break;
            case HELM:
                layout = new HelmLayout();
                break;
            case FILE:
                layout = new FileLayout();
                break;
            case NPM:
                layout = new NpmLayout();
                break;
            case GRADLE:
                layout = new GradleLayout();
                break;
            case COMPOSER:
                layout = new ComposerLayout();
                break;
            default:
                throw new IllegalArgumentException(
                    String.format("Unsupported repository type: %s", type)
                );
        }
        return layout;
    }

    /**
     * Get storage layout for the given repository type string.
     *
     * @param typeStr Repository type as string
     * @return Storage layout instance
     */
    public static StorageLayout forType(final String typeStr) {
        try {
            return forType(RepositoryType.valueOf(typeStr.toUpperCase(Locale.ROOT)));
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                String.format("Unknown repository type: %s", typeStr),
                ex
            );
        }
    }
}
