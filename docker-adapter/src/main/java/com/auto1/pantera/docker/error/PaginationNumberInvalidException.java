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
 * The {@code n} page size of a {@code _catalog} or {@code tags/list} request
 * is not a non-negative integer.
 *
 * @since 2.2.9
 */
@SuppressWarnings("serial")
public final class PaginationNumberInvalidException extends RuntimeException
    implements DockerError {

    /**
     * Ctor.
     *
     * @param value Page size as sent
     */
    public PaginationNumberInvalidException(final String value) {
        this(value, null);
    }

    /**
     * Ctor.
     *
     * @param value Page size as sent
     * @param cause Parse failure, may be null
     */
    public PaginationNumberInvalidException(final String value, final Throwable cause) {
        super(String.format("page size must be a non-negative integer, got `%s`", value), cause);
    }

    @Override
    public String code() {
        return "PAGINATION_NUMBER_INVALID";
    }

    @Override
    public String message() {
        return "invalid number of results requested";
    }

    @Override
    public Optional<String> detail() {
        return Optional.of(this.getMessage());
    }
}
