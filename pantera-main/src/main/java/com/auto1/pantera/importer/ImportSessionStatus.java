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
package com.auto1.pantera.importer;

/**
 * Persistent session status stored in PostgreSQL.
 *
 * @since 1.0
 */
public enum ImportSessionStatus {

    /**
     * Session is actively uploading.
     */
    IN_PROGRESS,

    /**
     * Session finished successfully.
     */
    COMPLETED,

    /**
     * Session detected checksum mismatch and artifact moved to quarantine.
     */
    QUARANTINED,

    /**
     * Session failed with non-recoverable error.
     */
    FAILED,

    /**
     * Session skipped because artifact already exists.
     */
    SKIPPED
}
