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
package com.auto1.pantera.rpm.pkg;

import com.auto1.pantera.rpm.Digest;
import java.io.IOException;

/**
 * RPM checksum.
 * @since 0.6
 */
public interface Checksum {

    /**
     * Digest.
     * @return Digest
     */
    Digest digest();

    /**
     * Checksum hex string.
     * @return Hex string
     * @throws IOException On error
     */
    String hex() throws IOException;

    /**
     * Simple {@link Checksum} implementation.
     * @since 0.11
     */
    final class Simple implements Checksum {

        /**
         * Digest.
         */
        private final Digest dgst;

        /**
         * Checksum hex.
         */
        private final String sum;

        /**
         * Ctor.
         * @param dgst Digest
         * @param sum Checksum hex
         */
        public Simple(final Digest dgst, final String sum) {
            this.dgst = dgst;
            this.sum = sum;
        }

        @Override
        public Digest digest() {
            return this.dgst;
        }

        @Override
        public String hex() throws IOException {
            return this.sum;
        }
    }
}
