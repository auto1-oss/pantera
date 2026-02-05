/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

/**
 * Caching infrastructure for Artipie.
 * <p>
 * Provides write-through caching with TTL support for proxy repositories.
 * </p>
 * <ul>
 *   <li>{@link com.artipie.cache.CachePolicy} - Determines if content is
 *       artifact (immutable) or metadata (mutable)</li>
 *   <li>{@link com.artipie.cache.StorageCache} - Write-through cache with
 *       TTL and background refresh</li>
 *   <li>{@link com.artipie.cache.BackgroundRefresh} - Async metadata refresh
 *       service</li>
 * </ul>
 *
 * @since 1.0
 */
package com.artipie.cache;
