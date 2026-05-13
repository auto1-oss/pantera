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
import com.auto1.pantera.rpm.FileChecksum;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.commons.io.FileUtils;
import org.redline_rpm.header.AbstractHeader;
import org.redline_rpm.header.Header;

/**
 * Single package in a file.
 *
 * @since 0.1
 */
public final class FilePackage implements Package {

    /**
     * File package metadata from package path. Used for tests and
     * benchmarks only.
     * @since 0.6
     */
    public static final class Headers implements Meta {

        /**
         * Native headers.
         */
        private final Header hdr;

        /**
         * File path.
         */
        private final Path file;

        /**
         * Digest.
         */
        private final Digest digest;

        /**
         * The RPM file location relatively to the updated repository.
         */
        private final String location;

        /**
         * Ctor.
         * @param hdr Native headers
         * @param file File path
         * @param digest Digest
         * @param location File relative location
                 */
        public Headers(final Header hdr, final Path file, final Digest digest,
            final String location) {
            this.hdr = hdr;
            this.file = file;
            this.digest = digest;
            this.location = location;
        }

        /**
         * Ctor for tests.
         * @param hdr Native headers
         * @param file File path
         * @param digest Digest
         */
        public Headers(final Header hdr, final Path file, final Digest digest) {
            this(hdr, file, digest, file.getFileName().toString());
        }

        @Override
        public MetaHeader header(final AbstractHeader.Tag tag) {
            return new EntryHeader(this.hdr.getEntry(tag));
        }

        @Override
        public Checksum checksum() {
            return new FileChecksum(this.file, this.digest);
        }

        @Override
        public long size() {
            return FileUtils.sizeOf(this.file.toFile());
        }

        @Override
        public String href() {
            return this.location;
        }

        @Override
        public int[] range() {
            return new int[]{
                this.hdr.getStartPos(),
                this.hdr.getEndPos(),
            };
        }
    }

    /**
     * {@link AbstractHeader.Entry} based MetaHeader.
     *
     * @since 0.6.3
     */
    public static final class EntryHeader implements MetaHeader {

        /**
         * Native header entry.
         */
        private final Optional<AbstractHeader.Entry<?>> entry;

        /**
         * Ctor.
         * @param entry Native header entry
         */
        public EntryHeader(final AbstractHeader.Entry<?> entry) {
            this(Optional.ofNullable(entry));
        }

        /**
         * Ctor.
         * @param entry Native header entry
         */
        EntryHeader(final Optional<AbstractHeader.Entry<?>> entry) {
            this.entry = entry;
        }

        @Override
        public String asString(final String def) {
            return this.entry
                .map(e -> ((String[]) e.getValues())[0])
                .orElse(def);
        }

        @Override
        public int asInt(final int def) {
            return this.entry
                .map(
                    e -> {
                        final int result;
                        if (e.getValues() instanceof short[]) {
                            result = ((short[]) e.getValues())[0];
                        } else {
                            result = ((int[]) e.getValues())[0];
                        }
                        return result;
                    }
                )
                .orElse(def);
        }

        @Override
        public List<String> asStrings() {
            return this.entry
                .map(e -> Arrays.asList((String[]) e.getValues()))
                .orElse(Collections.emptyList());
        }

        @Override
        public int[] asInts() {
            return this.entry
                .map(
                    e -> {
                        final int[] result;
                        if (e.getValues() instanceof short[]) {
                            final short[] sre = (short[]) e.getValues();
                            result = new int[sre.length];
                            // Widening short -> int element-by-element; cannot use
                            // System.arraycopy or Arrays.copyOf across primitive types.
                            for (int ind = 0; ind < sre.length; ind += 1) { // NOPMD AvoidArrayLoops - short[] -> int[] requires per-element widening
                                result[ind] = sre[ind];
                            }
                        } else {
                            result = (int[]) e.getValues();
                        }
                        return result;
                    }
                )
                .orElseGet(() -> new int[0]);
        }
    }
}
