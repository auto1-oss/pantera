/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.error;

import java.util.Optional;

/**
 * The operation was unsupported due to a missing implementation or invalid set of parameters.
 * See <a href="https://docs.docker.com/registry/spec/api/#errors-2">Errors</a>.
 *
 * @since 0.8
 */
public final class UnsupportedError implements DockerError {

    @Override
    public String code() {
        return "UNSUPPORTED";
    }

    @Override
    public String message() {
        return "The operation is unsupported.";
    }

    @Override
    public Optional<String> detail() {
        return Optional.empty();
    }
}
