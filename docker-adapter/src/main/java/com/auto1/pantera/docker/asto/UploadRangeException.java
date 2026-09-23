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
package com.auto1.pantera.docker.asto;

/**
 * A chunk was sent out of order: its declared start offset is not the
 * current end of the upload. The registry answers 416 and reports the
 * range it holds so the client can resume from there.
 *
 * @since 2.2.9
 */
@SuppressWarnings("serial")
public final class UploadRangeException extends RuntimeException {

    /**
     * Number of bytes the upload holds.
     */
    private final long uploaded;

    /**
     * @param uploaded Number of bytes the upload holds
     */
    public UploadRangeException(final long uploaded) {
        super(String.format("chunk must start at offset %d", uploaded));
        this.uploaded = uploaded;
    }

    /**
     * Offset of the last byte the upload holds, as reported in the
     * {@code Range} header.
     *
     * @return Offset, 0 when nothing was uploaded
     */
    public long offset() {
        return Math.max(this.uploaded - 1, 0);
    }
}
