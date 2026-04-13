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
package com.auto1.pantera.pypi.meta;

import java.util.stream.Stream;

/**
 * Python package info.
 * @since 0.6
 */
public interface PackageInfo {

    /**
     * Package name.
     * @return Name of the project
     */
    String name();

    /**
     * Package version.
     * @return Version of the project
     */
    String version();

    /**
     * A one-line summary of what the package does.
     * @return Summary
     */
    String summary();

    /**
     * Python version requirement from Requires-Python header.
     * @return Requires-Python value, or empty string if not specified
     */
    default String requiresPython() {
        return "";
    }

    /**
     * Implementation of {@link PackageInfo} that parses python metadata PKG-INFO file to obtain
     * required information. For more details see
     * <a href="https://www.python.org/dev/peps/pep-0314/">PEP-314</a>.
     * @since 0.6
     */
    final class FromMetadata implements PackageInfo {

        /**
         * Input.
         */
        private final String input;

        /**
         * Ctor.
         * @param input Input
         */
        public FromMetadata(final String input) {
            this.input = input;
        }

        @Override
        public String name() {
            return this.read("Name");
        }

        @Override
        public String version() {
            return this.read("Version");
        }

        @Override
        public String summary() {
            return this.read("Summary");
        }

        @Override
        public String requiresPython() {
            final String name = "Requires-Python:";
            return Stream.of(this.input.split("\n"))
                .filter(line -> line.startsWith(name)).findFirst()
                .map(line -> line.replace(name, "").trim())
                .orElse("");
        }

        /**
         * Reads header value by name.
         * @param header Header name
         * @return Header value
         */
        private String read(final String header) {
            final String name = String.format("%s:", header);
            return Stream.of(this.input.split("\n"))
                .filter(line -> line.startsWith(name)).findFirst()
                .map(line ->  line.replace(name, "").trim())
                .orElseThrow(
                    () -> new IllegalArgumentException(
                        String.format("Invalid metadata file, header %s not found", header)
                    )
                );
        }
    }
}
