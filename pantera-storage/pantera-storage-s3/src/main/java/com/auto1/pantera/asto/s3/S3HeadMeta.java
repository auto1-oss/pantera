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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Meta;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Metadata from S3 object.
 * @since 0.1
 */
final class S3HeadMeta implements Meta {

    /**
     * S3 head object response.
     */
    private final HeadObjectResponse rsp;

    /**
     * New metadata.
     * @param rsp Head response
     */
    S3HeadMeta(final HeadObjectResponse rsp) {
        this.rsp = rsp;
    }

    @Override
    public <T> T read(final ReadOperator<T> opr) {
        final Map<String, String> raw = new HashMap<>();
        Meta.OP_SIZE.put(raw, this.rsp.contentLength());
        // ETag is a quoted MD5 of blob content according to S3 docs
        Meta.OP_MD5.put(raw, this.rsp.eTag().replaceAll("\"", ""));
        return opr.take(raw);
    }
}
