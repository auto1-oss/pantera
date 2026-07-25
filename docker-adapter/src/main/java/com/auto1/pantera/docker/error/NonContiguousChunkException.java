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
package com.auto1.pantera.docker.error;

import java.util.Optional;

/**
 * A chunked-upload {@code PATCH} carried a {@code Content-Range} start
 * offset that does not match the number of bytes already received for this
 * upload. Per the Distribution spec this is {@code 416 Requested Range Not
 * Satisfiable}, not a silent accept — see WS4-docker.6.
 *
 * <p>Implements {@link DockerError} for its JSON body, but is caught
 * explicitly by {@code PatchUploadSlice} rather than left to {@code
 * ErrorHandlingSlice}'s generic {@code DockerError} dispatch (which maps to
 * {@code 400}) — {@code 416} is the correct status here.
 */
@SuppressWarnings("serial")
public final class NonContiguousChunkException extends RuntimeException implements DockerError {

    /**
     * @param expected Byte offset the upload actually expects next.
     * @param declared Byte offset the client's {@code Content-Range} claimed.
     */
    public NonContiguousChunkException(final long expected, final long declared) {
        super(String.format("expected chunk starting at %d, got %d", expected, declared));
    }

    @Override
    public String code() {
        return "BLOB_UPLOAD_INVALID";
    }

    @Override
    public String message() {
        return "blob upload invalid: non-contiguous chunk";
    }

    @Override
    public Optional<String> detail() {
        return Optional.ofNullable(this.getMessage());
    }
}
