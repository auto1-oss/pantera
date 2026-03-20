/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.importer;

import com.auto1.pantera.importer.api.DigestType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Result of an import attempt.
 *
 * @since 1.0
 */
public final class ImportResult {

    /**
     * Status.
     */
    private final ImportStatus status;

    /**
     * Human readable message.
     */
    private final String message;

    /**
     * Calculated digests.
     */
    private final Map<DigestType, String> digests;

    /**
     * Artifact size.
     */
    private final long size;

    /**
     * Optional quarantine key for mismatched uploads.
     */
    private final String quarantineKey;

    /**
     * Ctor.
     *
     * @param status Status
     * @param message Message
     * @param digests Digests
     * @param size Artifact size
     * @param quarantineKey Quarantine key
     */
    ImportResult(
        final ImportStatus status,
        final String message,
        final Map<DigestType, String> digests,
        final long size,
        final String quarantineKey
    ) {
        this.status = status;
        this.message = message;
        this.digests = digests == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new EnumMap<>(digests));
        this.size = size;
        this.quarantineKey = quarantineKey;
    }

    public ImportStatus status() {
        return this.status;
    }

    public String message() {
        return this.message;
    }

    public Map<DigestType, String> digests() {
        return this.digests;
    }

    public long size() {
        return this.size;
    }

    public Optional<String> quarantineKey() {
        return Optional.ofNullable(this.quarantineKey);
    }
}
