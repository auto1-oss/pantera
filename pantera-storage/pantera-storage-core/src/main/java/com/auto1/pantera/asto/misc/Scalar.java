/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
