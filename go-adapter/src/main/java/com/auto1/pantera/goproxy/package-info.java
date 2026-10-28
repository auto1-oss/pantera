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
 * Go proxy metadata event processing.
 *
 * <p>{@link com.auto1.pantera.goproxy.GoProxyPackageProcessor} is the
 * Quartz job that drains the {@code go-proxy} artifact-event queue and
 * writes module metadata to the DB/index/audit trail. The legacy
 * {@code Goproxy} server-side zip-construction helper (270 lines,
 * referenced only by its own now-deleted tests) was removed as dead code
 * (WS4-go.7) — hosted publish uses {@link
 * com.auto1.pantera.http.GoUploadSlice}, proxy caching uses {@code
 * CachedProxySlice}.</p>
 *
 * @since 2.3.0
 */
package com.auto1.pantera.goproxy;

