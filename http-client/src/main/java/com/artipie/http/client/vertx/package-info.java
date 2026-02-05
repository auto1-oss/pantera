/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

/**
 * Vert.x-based HTTP client implementation.
 * <p>
 * Provides HTTP client functionality using Vert.x WebClient with:
 * <ul>
 *   <li>HTTP/2 with ALPN support and HTTP/1.1 fallback</li>
 *   <li>Connection pooling per destination</li>
 *   <li>Retry policy with exponential backoff and jitter</li>
 *   <li>Circuit breaker per destination</li>
 * </ul>
 *
 * @since 1.21.0
 */
package com.artipie.http.client.vertx;
