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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.audit.AuditContext;
import io.vertx.ext.web.RoutingContext;

/**
 * Trace id and client IP of a management API request, carried on the
 * routing context so an audit record written after an async hop (where the
 * MDC is gone) still has them.
 *
 * @since 2.2.9
 */
final class ApiAuditContext {

    /**
     * Routing-context key of the trace id.
     */
    private static final String TRACE = "pantera.audit.trace.id";

    /**
     * Routing-context key of the client IP.
     */
    private static final String CLIENT_IP = "pantera.audit.client.ip";

    /**
     * Routing context.
     */
    private final RoutingContext ctx;

    /**
     * Ctor.
     * @param ctx Routing context
     */
    ApiAuditContext(final RoutingContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Record the request's trace id and client IP.
     * @param trace Trace id
     * @param clientIp Client IP, nullable
     */
    void bind(final String trace, final String clientIp) {
        this.ctx.put(TRACE, trace);
        if (clientIp != null && !clientIp.isBlank()) {
            this.ctx.put(CLIENT_IP, clientIp);
        }
    }

    /**
     * The audit context of this request.
     * @return Audit context (fields null when the request carried none)
     */
    AuditContext value() {
        return new AuditContext(this.ctx.get(TRACE), this.ctx.get(CLIENT_IP));
    }
}
