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

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.ThreadContext;

/**
 * Emits one audit record so that its MDC-owned identity comes only from the
 * record's explicit fields, never from the emitting thread's MDC.
 *
 * <p>{@link EcsLogger#log()} follows an "MDC wins" rule for MDC-owned keys
 * ({@code trace.id}, {@code client.ip}, {@code user.name},
 * {@code repository.*}, {@code package.name/version}): a value passed via
 * {@code field()} is dropped whenever the thread's context already holds that
 * key. Audit records are routinely emitted on pooled threads — storage
 * executors, Jetty HTTP-client threads, the event loop — whose context still
 * holds an earlier, unrelated request's values, so under that rule a record
 * silently took the other request's trace id, client IP and user, and a field
 * the record leaves empty was filled from the stale context. This class
 * withholds those keys from the thread context for exactly the duration of
 * the emit, so the record's own {@code field()} values (from its
 * {@link AuditContext} and identity arguments) are the ones written and an
 * absent value stays absent; the thread's context is restored afterwards.
 * Log4j snapshots the context into the event at log time, so the restore
 * cannot affect the emitted record.
 *
 * <p>When the thread's trace id is not the record's, the thread's span /
 * transaction ids belong to another request too and are withheld as well.
 *
 * @since 2.2.9
 */
final class AuditCorrelation {

    /**
     * MDC-owned keys every audit record sets explicitly.
     */
    private static final List<String> RECORD_KEYS = List.of(
        EcsMdc.TRACE_ID, EcsMdc.CLIENT_IP, EcsMdc.USER_NAME,
        EcsMdc.REPO_TYPE, EcsMdc.REPO_NAME, EcsMdc.PACKAGE_NAME, EcsMdc.PACKAGE_VERSION
    );

    /**
     * Span-scoped keys that are only meaningful alongside their own trace id.
     */
    private static final List<String> SPAN_KEYS = List.of(
        EcsMdc.SPAN_ID, EcsMdc.PARENT_SPAN_ID, EcsMdc.TRANSACTION_ID
    );

    /**
     * Request correlation of the record.
     */
    private final AuditContext ctx;

    /**
     * Ctor.
     *
     * @param ctx Request correlation of the record
     */
    AuditCorrelation(final AuditContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Log the record with the thread's MDC-owned identity withheld, then
     * restore the thread's context.
     *
     * @param logger Record builder carrying the record's explicit fields
     */
    void emit(final EcsLogger logger) {
        final List<String> withheld = new ArrayList<>(RECORD_KEYS);
        if (!Objects.equals(ThreadContext.get(EcsMdc.TRACE_ID), this.ctx.traceId())) {
            withheld.addAll(SPAN_KEYS);
        }
        final Map<String, String> saved = new HashMap<>(withheld.size());
        for (final String key : withheld) {
            final String value = ThreadContext.get(key);
            if (value != null) {
                saved.put(key, value);
            }
        }
        try {
            ThreadContext.removeAll(withheld);
            logger.log();
        } finally {
            ThreadContext.putAll(saved);
        }
    }
}
