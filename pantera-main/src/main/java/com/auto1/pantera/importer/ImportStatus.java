/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.importer;

/**
 * Import outcome status.
 *
 * @since 1.0
 */
public enum ImportStatus {

    /**
     * Artifact persisted successfully.
     */
    CREATED,

    /**
     * Artifact already existed and matched checksums.
     */
    ALREADY_PRESENT,

    /**
     * Artifact quarantined due to checksum mismatch.
     */
    CHECKSUM_MISMATCH,

    /**
     * Artifact rejected because repository metadata is missing.
     */
    INVALID_METADATA,

    /**
     * Import deferred for retry due to transient issue.
     */
    RETRY_LATER,

    /**
     * Unexpected failure.
     */
    FAILED
}
