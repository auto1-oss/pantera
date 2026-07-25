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

import com.auto1.pantera.asto.factory.PanteraStorageFactory;
import software.amazon.awssdk.services.s3.model.StorageClass;

/**
 * Factory to create S3 Express One Zone storage.
 *
 * S3 Express One Zone provides single-digit millisecond data access with consistent performance
 * for latency-sensitive applications. This storage class is optimized for performance and is
 * designed for workloads that require the fastest access to data.
 *
 * Key features:
 * - Single availability zone storage
 * - Up to 10x faster than S3 Standard
 * - Lower request costs
 * - Ideal for analytics, ML training, and interactive applications
 *
 * Configuration example:
 * <pre>{@code
 * storage:
 *   type: s3-express
 *   bucket: my-bucket--usw2-az1--x-s3  # Must use directory bucket naming format
 *   region: us-west-2
 *   credentials:
 *     type: basic
 *     accessKeyId: AKIAIOSFODNN7EXAMPLE
 *     secretAccessKey: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
 * }</pre>
 *
 * <p>Every configuration key, credential type, HTTP tuning option, multipart/parallel-download
 * setting, and the presign path are inherited unchanged from {@link S3StorageFactory} -- S3
 * Express One Zone only differs in two defaults: virtual-hosted-style access (S3 Express
 * requires path-style disabled) and the {@code EXPRESS_ONEZONE} storage class. Both remain
 * overridable via the ordinary {@code path-style} / {@code storage-class} config keys (WS1.0
 * fold, spec &sect;I: "{@code S3ExpressStorageFactory} folds in -- it only differs by
 * endpoint/config").</p>
 *
 * @since 1.18.0
 */
@PanteraStorageFactory("s3-express")
public final class S3ExpressStorageFactory extends S3StorageFactory {

    @Override
    protected boolean defaultPathStyle() {
        return false;
    }

    @Override
    protected StorageClass defaultStorageClass() {
        return StorageClass.EXPRESS_ONEZONE;
    }
}
