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
 * When a blob is uploaded,
 * the registry will check that the content matches the digest provided by the client.
 * The error may include a detail structure with the key “digest”,
 * including the invalid digest string.
 * This error may also be returned when a manifest includes an invalid layer digest.
 * See <a href="https://docs.docker.com/registry/spec/api/#errors-2">Errors</a>.
 *
 * @since 0.9
 */
@SuppressWarnings("serial")
public final class InvalidDigestException extends RuntimeException implements DockerError {

    /**
     * Ctor.
     *
     * @param details Error details.
     */
    public InvalidDigestException(final String details) {
        super(details);
    }

    @Override
    public String code() {
        return "DIGEST_INVALID";
    }

    @Override
    public String message() {
        return "provided digest did not match uploaded content";
    }

    @Override
    public Optional<String> detail() {
        return Optional.ofNullable(this.getMessage());
    }
}
