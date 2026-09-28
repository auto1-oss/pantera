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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.audit.AuditEvent;
import com.auto1.pantera.audit.AuditServiceRegistry;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Records one admin cache mutation: an application log line
 * ({@code event.category=configuration}) and an admin audit event, both
 * attributed to the authenticated caller and the request's client IP.
 *
 * @since 2.2.9
 */
final class AdminMutation {

    /**
     * Routing-context key the API entry handler binds the client IP under
     * (see {@code ApiAuditContext}).
     */
    private static final String CLIENT_IP_KEY = "pantera.audit.client.ip";

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.api.v1.admin";

    /**
     * Request.
     */
    private final RoutingContext ctx;

    /**
     * Ctor.
     *
     * @param ctx Routing context of the admin request
     */
    AdminMutation(final RoutingContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Authenticated caller.
     *
     * @return User name, {@code unknown} when absent
     */
    String actor() {
        final String user;
        if (this.ctx.user() != null && this.ctx.user().principal() != null) {
            user = this.ctx.user().principal().getString("sub", "unknown");
        } else {
            user = "unknown";
        }
        return user;
    }

    /**
     * Log and audit a completed (or failed) mutation.
     *
     * @param action snake_case event action, e.g. {@code neg_cache_invalidate}
     * @param audit Audit action verb (SCREAMING_SNAKE_CASE)
     * @param target Audit target
     * @param message Human-readable detail for the log line
     * @param details Structured audit payload
     * @param error Failure, or null on success
     */
    void record(
        final String action, final String audit, final String target,
        final String message, final Map<String, Object> details, final Throwable error
    ) {
        final String actor = this.actor();
        final EcsLogger log = error == null ? EcsLogger.warn(LOGGER) : EcsLogger.error(LOGGER);
        log.message(message)
            .eventCategory("configuration")
            .eventAction(action)
            .eventOutcome(error == null ? "success" : "failure")
            .field("user.name", actor);
        if (error != null) {
            log.error(error);
        }
        log.field("log.source", "application").log();
        final Object bound = this.ctx.get(CLIENT_IP_KEY);
        final String clientIp = bound instanceof String str && !str.isBlank()
            ? str : MDC.get(EcsMdc.CLIENT_IP);
        AuditServiceRegistry.instance().sharedService().record(
            new AuditEvent(Instant.now(), actor, audit, target, details, error == null, clientIp)
        );
    }
}
