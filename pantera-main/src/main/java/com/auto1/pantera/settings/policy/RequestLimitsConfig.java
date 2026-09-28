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
package com.auto1.pantera.settings.policy;

import com.auto1.pantera.settings.repo.FsStorageRootPolicy;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Validated request &amp; storage limits: the hard cap on a single request
 * body and the approved roots for inline {@code fs} repository storage.
 * Immutable; the {@link FsStorageRootPolicy} is parsed once here so
 * per-request consumers never re-parse.
 *
 * @since 2.2.9
 */
public final class RequestLimitsConfig {

    /**
     * Smallest accepted body cap (1 MiB) -- below this no client can publish.
     */
    public static final long MIN_REQUEST_BODY_BYTES = 1024L * 1024L;

    /**
     * Default body cap: 10 GiB.
     */
    public static final long DEFAULT_MAX_REQUEST_BODY_BYTES = 10L * 1024L * 1024L * 1024L;

    /**
     * Body cap in bytes.
     */
    private final long maxRequestBodyBytes;

    /**
     * Approved roots, path-separator delimited, as configured.
     */
    private final String fsStorageRoots;

    /**
     * Parsed root policy.
     */
    private final FsStorageRootPolicy fsRootPolicy;

    /**
     * Ctor; validates every field.
     * @param maxRequestBodyBytes Body cap, at least {@value #MIN_REQUEST_BODY_BYTES}
     * @param fsStorageRoots Path-separator delimited absolute directories, non-empty
     */
    public RequestLimitsConfig(final long maxRequestBodyBytes, final String fsStorageRoots) {
        if (maxRequestBodyBytes < MIN_REQUEST_BODY_BYTES) {
            throw new IllegalArgumentException(
                "max_request_body_bytes must be at least " + MIN_REQUEST_BODY_BYTES + " (1 MiB)"
            );
        }
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.fsStorageRoots = RequestLimitsConfig.validRoots(fsStorageRoots);
        this.fsRootPolicy = FsStorageRootPolicy.parse(this.fsStorageRoots);
    }

    /**
     * Documented defaults.
     * @return Config
     */
    public static RequestLimitsConfig defaults() {
        return new RequestLimitsConfig(DEFAULT_MAX_REQUEST_BODY_BYTES, FsStorageRootPolicy.DEFAULT);
    }

    /**
     * Body cap.
     * @return Bytes
     */
    public long maxRequestBodyBytes() {
        return this.maxRequestBodyBytes;
    }

    /**
     * Approved roots as configured.
     * @return Path-separator delimited list
     */
    public String fsStorageRoots() {
        return this.fsStorageRoots;
    }

    /**
     * Parsed root policy.
     * @return Policy
     */
    public FsStorageRootPolicy fsRootPolicy() {
        return this.fsRootPolicy;
    }

    /**
     * Validate a roots spec: at least one entry, every entry an absolute path.
     * @param spec Raw spec
     * @return Trimmed spec
     */
    private static String validRoots(final String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("fs_storage_roots must list at least one absolute directory");
        }
        int entries = 0;
        for (final String item : spec.split(File.pathSeparator)) {
            if (item.isBlank()) {
                continue;
            }
            entries += 1;
            try {
                if (!Path.of(item.trim()).isAbsolute()) {
                    throw new IllegalArgumentException(
                        "fs_storage_roots entries must be absolute paths: " + item.trim()
                    );
                }
            } catch (final InvalidPathException bad) {
                throw new IllegalArgumentException("fs_storage_roots entry is not a valid path: " + item.trim(), bad);
            }
        }
        if (entries == 0) {
            throw new IllegalArgumentException("fs_storage_roots must list at least one absolute directory");
        }
        return spec.trim();
    }
}
