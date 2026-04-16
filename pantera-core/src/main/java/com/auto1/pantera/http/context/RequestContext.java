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
package com.auto1.pantera.http.context;

/**
 * Minimal per-request envelope carrying the fields needed by
 * {@code com.auto1.pantera.http.fault.FaultTranslator#translate}.
 *
 * <p><b>Scaffold notice (WI-01):</b> this record is intentionally minimal. WI-02
 * will expand it with the full ECS-native field set documented in §3.3 of
 * {@code docs/analysis/v2.2-target-architecture.md} — including
 * {@code transactionId}, {@code spanId}, {@code userName}, {@code clientIp},
 * {@code userAgent}, {@code repoType}, {@code artifact}, {@code urlPath},
 * and {@code Deadline}, along with ThreadContext/APM propagation helpers.
 * For WI-01 only the four fields below are populated; the rest will be
 * added by WI-02 without changing the class name or package.
 *
 * @param traceId       ECS: trace.id — from the APM transaction / request edge.
 * @param httpRequestId ECS: http.request.id — unique per HTTP request (X-Request-ID
 *                      header, else a generated UUID).
 * @param repoName      ECS: repository.name — Pantera-specific. May be empty for
 *                      requests that are not yet resolved to a repository.
 * @param urlOriginal   ECS: url.original — the URL as the client sent it.
 * @since 2.2.0
 */
public record RequestContext(
    String traceId,
    String httpRequestId,
    String repoName,
    String urlOriginal
) {
}
