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
package com.auto1.pantera.rpm;

import com.auto1.pantera.rpm.meta.XmlPackage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * RPM repository metadata files naming policy.
 * @since 0.3
 */
public interface NamingPolicy {

    /**
     * Name for source with its content, without file extension.
     * @param source Metadata file name
     * @param content Metadata file content
     * @return File name
     * @throws IOException On error
     */
    String name(String source, Path content) throws IOException;

    /**
     * Full relative path for the metadata source file, with extension. This path is build as
     * `metadata/[prefix]-[sourse-name].xml.gz`. Can be used as a storage key.
     * @param source Source metadata
     * @param prefix Source prefix
     * @return File name
     */
    String fullName(XmlPackage source, String prefix);

    /**
     * Add hash prefix to names.
     * @since 0.3
     */
    final class HashPrefixed implements NamingPolicy {

        /**
         * Message digest supplier.
         */
        private final Digest dgst;

        /**
         * Ctor.
         * @param dgst One of the supported digest algorithms
         */
        public HashPrefixed(final Digest dgst) {
            this.dgst = dgst;
        }

        @Override
        public String name(final String source, final Path content) throws IOException {
            return String.format("%s-%s", new FileChecksum(content, this.dgst).hex(), source);
        }

        @Override
        public String fullName(final XmlPackage source, final String prefix) {
            return String.format("repodata/%s-%s.xml.gz", prefix, source.lowercase());
        }
    }
}
