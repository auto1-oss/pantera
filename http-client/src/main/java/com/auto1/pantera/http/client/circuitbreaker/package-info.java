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
 * Per-host upstream circuit breaker. Trips on the first qualifying
 * failure (5xx / IO) and fast-fails subsequent outbound
 * calls for a Fibonacci-spaced cooldown, with a daemon HEAD probe at
 * each block-expiry instant (wired by the upstream client decorator).
 *
 * <p>Pairs with the reactive 429 / 503-with-Retry-After gate in
 * {@link com.auto1.pantera.http.client.ratelimit}. The two together
 * implement the canonical defence-in-depth described in
 * {@code analysis/reference/canonical-architecture.md} §6 and close
 * gap G6 from {@code analysis/reference/gap-analysis.md}.
 *
 * <p>Inspired by Sonatype Nexus's {@code BlockingHttpClient} pattern:
 * one qualifying failure trips the breaker; the upstream sees no
 * traffic for the cooldown window; a single HEAD probe at each
 * boundary re-tests; success resets the sequence. At 100 r/s during
 * a 60 s outage the upstream sees at most 3 wire requests instead of
 * 6 000.
 *
 * @since 2.2.0
 */
package com.auto1.pantera.http.client.circuitbreaker;
