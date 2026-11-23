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
package com.auto1.pantera.repojob;

/**
 * A repository operation refused for a reason the caller can act on.
 *
 * @since 2.2.14
 */
public final class RepoOpRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * HTTP status to answer.
     */
    private final int code;

    /**
     * API error code.
     */
    private final String error;

    /**
     * Ctor.
     * @param code HTTP status to answer
     * @param error API error code
     * @param message Why the operation is refused
     */
    public RepoOpRejected(final int code, final String error, final String message) {
        this(code, error, message, null);
    }

    /**
     * Ctor.
     * @param code HTTP status to answer
     * @param error API error code
     * @param message Why the operation is refused
     * @param cause What made it so, nullable
     */
    public RepoOpRejected(
        final int code, final String error, final String message, final Throwable cause
    ) {
        super(message, cause);
        this.code = code;
        this.error = error;
    }

    /**
     * HTTP status to answer.
     * @return Status code
     */
    public int status() {
        return this.code;
    }

    /**
     * API error code.
     * @return Code such as NOT_FOUND or CONFLICT
     */
    public String error() {
        return this.error;
    }
}
