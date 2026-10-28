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
package com.auto1.pantera.asto.blob;

import com.auto1.pantera.asto.Key;
import java.net.URI;

/**
 * Issues time-limited, backend-signed URLs that grant direct read access to a
 * single blob, bypassing Pantera for the byte transfer.
 *
 * <p>This is the signing half of the presigned direct-download read strategy
 * (spec WS1 &sect;B2): for cloud deployments it is the primary way to serve
 * artifact bytes at scale, because Pantera stops streaming bytes for hits
 * entirely -- the cost of a redirect becomes an index lookup plus local signing,
 * not a blob-store round trip. Serving the {@code 302} response and enforcing
 * which routes are eligible (immutable byte objects only, never metadata) is a
 * later phase (WS1.7); this interface only covers issuing the URL.</p>
 *
 * <p><strong>Implementations MUST sign entirely locally</strong> -- SigV4 for S3
 * and S3-API-compatible stores, V4 signed URLs for GCS, SAS tokens for Azure --
 * with no network round trip to the blob store. That is what makes it safe to call
 * {@link #presignGet(Key, long)} from latency-sensitive request handling, including
 * the Vert.x event loop: the work is local cryptographic signing, not I/O.</p>
 *
 * @since 2.3.0
 */
public interface Presigner {

    /**
     * Issue a presigned GET URL for {@code key}, valid for {@code ttlSeconds}.
     *
     * @param key Blob key.
     * @param ttlSeconds Validity window in seconds; must be positive.
     * @return A URI the client can {@code GET} directly from the blob store.
     * @throws IllegalArgumentException if {@code ttlSeconds} is not positive.
     * @throws IllegalStateException if this instance was not configured with the
     *  credentials/region needed to sign locally.
     */
    URI presignGet(Key key, long ttlSeconds);

    /**
     * Whether this instance is actually able to sign right now (WS1.7, spec
     * {@code WS1-storage-for-scale.md} &sect;3.B2) -- lets a caller decide
     * whether to attempt {@link #presignGet(Key, long)} at all without
     * relying on catching {@link IllegalStateException} as control flow.
     * {@code true} by default; an implementation built without the
     * credentials/region/endpoint needed to sign (e.g. {@link
     * com.auto1.pantera.asto.s3.S3Storage} constructed via the short
     * constructor that skips presign support) overrides this to {@code
     * false}, and a caller MUST then fall back to streaming rather than
     * calling {@link #presignGet(Key, long)}.
     *
     * @return {@code true} iff {@link #presignGet(Key, long)} can be called
     *  safely right now.
     */
    default boolean isPresignConfigured() {
        return true;
    }
}
