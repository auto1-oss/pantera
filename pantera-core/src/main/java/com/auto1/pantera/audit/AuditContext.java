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
package com.auto1.pantera.audit;

/**
 * Correlation context threaded explicitly into every {@link AuditLogger} call.
 *
 * <p>Replaces the pre-2026-07 pattern of reading {@code trace.id} / {@code client.ip}
 * from MDC inside {@link AuditLogger} itself. That pattern silently produced empty
 * fields whenever the calling code ran on a thread that never had
 * {@code EcsLoggingSlice}'s MDC bound to it — Quartz worker threads, RxJava
 * continuations, and any adapter that forgot to call
 * {@link com.auto1.pantera.http.log.RequestContextHeaders#bindToMdc}. There was no
 * compiler-enforced way to catch a missing bind; the field was just quietly absent
 * in the shipped audit log.
 *
 * <p>{@link AuditLogger}'s methods require an {@code AuditContext} argument, so a
 * caller cannot emit an audit event without deciding where {@code traceId} and
 * {@code clientIp} come from. Both fields are optional (nullable) at the type level
 * because a handful of legitimate callers — CLI tools, background jobs with no
 * originating request — genuinely have neither; {@link AuditLogger} passes them to
 * {@code EcsLogger.field(...)}, which already no-ops on {@code null}.
 *
 * @param traceId  ECS {@code trace.id}, or {@code null} if none is available
 * @param clientIp ECS {@code client.ip}, or {@code null} if none is available
 * @since 2.2.0
 */
public record AuditContext(String traceId, String clientIp) {

    /**
     * Sentinel for callers with no request context at all (CLI tools, startup-time
     * jobs). Prefer threading the real context when one exists; use this only when
     * there genuinely is none.
     */
    public static final AuditContext NONE = new AuditContext(null, null);
}
