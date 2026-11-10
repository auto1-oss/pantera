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
/**
 * Backend-agnostic blob-store abstraction (WS1.0 of the 2.3.0 storage-for-scale
 * rebuild): {@link com.auto1.pantera.asto.blob.BlobStore} and
 * {@link com.auto1.pantera.asto.blob.Presigner} let the cache/index/write-back/
 * redirect machinery built on top (WS1.1+) work against any object store -- S3 and
 * S3-API-compatible services today, native GCS/Azure later -- without depending on
 * a specific SDK.
 *
 * @since 2.3.0
 */
package com.auto1.pantera.asto.blob;
