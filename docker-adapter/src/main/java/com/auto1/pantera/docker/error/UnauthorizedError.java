/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.error;

import java.util.Optional;

/**
 * Client unauthorized error.
 *
 * @since 0.5
 */
public final class UnauthorizedError implements DockerError {

    @Override
    public String code() {
        return "UNAUTHORIZED";
    }

    @Override
    public String message() {
        return "authentication required";
    }

    @Override
    public Optional<String> detail() {
        return Optional.empty();
    }
}
