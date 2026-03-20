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
package com.auto1.pantera.debian.metadata;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * Control file field.
 * See <a href="https://www.debian.org/doc/debian-policy/ch-controlfields.html">docs</a>.
 * @since 0.1
 */
public interface ControlField {

    /**
     * Control file field values.
     * @param control Control file as string
     * @return Values of the field
     */
    List<String> value(String control);

    /**
     * {@link ControlField} by field name.
     * @since 0.1
     */
    abstract class ByName implements ControlField {

        /**
         * Field name.
         */
        private final String field;

        /**
         * Ctor.
         * @param field Field
         */
        protected ByName(final String field) {
            this.field = field;
        }

        @Override
        public List<String> value(final String control) {
            return Stream.of(control.split("\n")).filter(item -> item.startsWith(this.field))
                .findFirst()
                .map(item -> item.substring(item.indexOf(":") + 2))
                .map(res -> res.split(" "))
                .map(Arrays::asList)
                .orElseThrow(
                    () -> new NoSuchElementException(
                        String.format("Field %s not found in control", this.field)
                    )
                );
        }
    }

    /**
     * Architecture.
     * @since 0.1
     */
    final class Architecture extends ByName {

        /**
         * Ctor.
         */
        public Architecture() {
            super("Architecture");
        }
    }

    /**
     * Package.
     * @since 0.5
     */
    final class Package extends ByName {

        /**
         * Ctor.
         */
        public Package() {
            super("Package");
        }
    }

    /**
     * Version.
     * @since 0.5
     */
    final class Version extends ByName {

        /**
         * Ctor.
         */
        public Version() {
            super("Version");
        }
    }

    /**
     * Filename.
     * @since 0.5
     */
    final class Filename extends ByName {

        /**
         * Ctor.
         */
        public Filename() {
            super("Filename");
        }
    }
}
