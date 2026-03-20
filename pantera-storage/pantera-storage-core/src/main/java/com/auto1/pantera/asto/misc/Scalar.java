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
package com.auto1.pantera.asto.misc;

/**
 * Scalar.
 * Originally introduced in <a href="https://github.com/yegor256/cactoos">cactoos</a>.
 * @param <T> Result value type
 * @since 1.3
 */
@FunctionalInterface
public interface Scalar<T> {

    /**
     * Convert it to the value.
     * @return The value
     * @throws Exception If fails
     */
    T value() throws Exception;

}
