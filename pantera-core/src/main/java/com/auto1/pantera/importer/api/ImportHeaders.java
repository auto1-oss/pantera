/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.importer.api;

/**
 * Common HTTP header names used by the Artipie import pipeline.
 *
 * <p>The CLI and server share these constants to guarantee consistent
 * semantics for resumable uploads, checksum handling and metadata propagation.</p>
 *
 * @since 1.0
 */
public final class ImportHeaders {

    /**
     * Idempotency key header.
     */
    public static final String IDEMPOTENCY_KEY = "X-Artipie-Idempotency-Key";

    /**
     * Repo type header value.
     */
    public static final String REPO_TYPE = "X-Artipie-Repo-Type";

    /**
     * Artifact name header.
     */
    public static final String ARTIFACT_NAME = "X-Artipie-Artifact-Name";

    /**
     * Artifact version header.
     */
    public static final String ARTIFACT_VERSION = "X-Artipie-Artifact-Version";

    /**
     * Artifact size header (in bytes).
     */
    public static final String ARTIFACT_SIZE = "X-Artipie-Artifact-Size";

    /**
     * Artifact owner header.
     */
    public static final String ARTIFACT_OWNER = "X-Artipie-Artifact-Owner";

    /**
     * Artifact created timestamp header (milliseconds epoch).
     */
    public static final String ARTIFACT_CREATED = "X-Artipie-Artifact-Created";

    /**
     * Artifact release timestamp header (milliseconds epoch).
     */
    public static final String ARTIFACT_RELEASE = "X-Artipie-Artifact-Release";

    /**
     * SHA-1 checksum header.
     */
    public static final String CHECKSUM_SHA1 = "X-Artipie-Checksum-Sha1";

    /**
     * SHA-256 checksum header.
     */
    public static final String CHECKSUM_SHA256 = "X-Artipie-Checksum-Sha256";

    /**
     * MD5 checksum header.
     */
    public static final String CHECKSUM_MD5 = "X-Artipie-Checksum-Md5";

    /**
     * Checksum policy header.
     */
    public static final String CHECKSUM_POLICY = "X-Artipie-Checksum-Mode";

    /**
     * Optional flag to mark metadata-only uploads.
     */
    public static final String METADATA_ONLY = "X-Artipie-Metadata-Only";

    /**
     * Prevent instantiation.
     */
    private ImportHeaders() {
        // utility
    }
}
