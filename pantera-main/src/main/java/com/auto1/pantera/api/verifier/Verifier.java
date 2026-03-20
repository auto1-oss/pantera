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
