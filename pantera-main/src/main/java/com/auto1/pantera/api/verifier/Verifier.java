/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api.verifier;

/**
 * Validates a condition and provides error message.
 * @since 0.26
 */
public interface Verifier {
    /**
     * Validate condition.
     * @return True if successful result of condition
     */
    boolean valid();

    /**
     * Get error message in case error result of condition.
     * @return Error message if not successful
     */
    String message();
}
