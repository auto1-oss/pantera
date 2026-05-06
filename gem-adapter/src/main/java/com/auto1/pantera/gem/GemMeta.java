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
package com.auto1.pantera.gem;

import java.nio.file.Path;

/**
 * Gem metadata parser.
 * @since 1.0
 */
public interface GemMeta {

    /**
     * Extract Gem info.
     * @param gem Path to gem
     * @return JSON object
     */
    MetaInfo info(Path gem);

    /**
     * Gem info metadata format.
     * @since 1.0
     */
    interface MetaFormat {

        /**
         * Print info string.
         * @param name Key
         * @param value String
         */
        void print(String name, String value);

        /**
         * Print info child.
         * @param name Key
         * @param value Node
         */
        void print(String name, MetaInfo value);

        /**
         * Print array of strings.
         * @param name Key
         * @param values Array
         */
        void print(String name, String[] values);
    }

    /**
     * Metadata info.
     * @since 1.0
     */
    interface MetaInfo {
        /**
         * Print meta info using format.
         * @param fmt Meta format
         */
        void print(MetaFormat fmt);

        /**
         * Print info to string using format provided.
         * @param fmt Format of printing
         * @return String
         */
        default String toString(final MetaFormat fmt) {
            this.print(fmt);
            return fmt.toString();
        }
    }
}
