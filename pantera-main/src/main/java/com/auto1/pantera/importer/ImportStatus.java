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
