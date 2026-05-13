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
 * Outbound rate-limit + 429 / Retry-After gate. Wraps every per-host
 * Jetty client slice with a token-bucket governor so Pantera never
 * exceeds the rate its upstream registries tolerate.
 *
 * <p>See {@code analysis/plan/v1/PLAN.md} workstream W2 (milestone M3).
 *
 * @since 2.2.0
 */
package com.auto1.pantera.http.client.ratelimit;
