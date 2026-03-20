/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
