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
package com.auto1.pantera.http.log;

import com.auto1.pantera.http.Headers;
import java.net.URI;

/**
 * The application log record explaining why Pantera answers a request with a
 * 5xx: one record per synthesised 5xx, emitted where the cause is known.
 *
 * <p>The access log only says <i>that</i> a request got a 502; this record
 * says <i>why</i> — {@code event.reason} (a bounded token: an upstream status
 * like {@code upstream_status_503}, {@code circuit_open},
 * {@code connect_timeout}, {@code storage_unavailable}, ...), the answered
 * {@code http.response.status_code}, the upstream {@code url.full} /
 * {@code destination.address} when there is one, and {@code error.*} when
 * there is a throwable. It carries the request's {@code trace.id} from the
 * internal context headers, so it joins the access-log line of the same
 * request even when it is emitted on a pooled thread. Nothing of it ever
 * reaches the client.</p>
 *
 * <pre>{@code
 * new ServerErrorLog("com.auto1.pantera.maven.http", "proxy_upstream", headers)
 *     .repository(repo).path(path).upstream(upstreamUri)
 *     .status(502).failure(err)
 *     .warn("Upstream request failed");
 * }</pre>
 *
 * @since 2.2.11
 */
public final class ServerErrorLog {

    /**
     * Logger name.
     */
    private final String logger;

    /**
     * ECS event.action.
     */
    private final String action;

    /**
     * Request headers carrying the internal correlation context.
     */
    private final Headers headers;

    /**
     * Repository name, or null.
     */
    private String repository;

    /**
     * Request path, or null.
     */
    private String path;

    /**
     * Upstream URL, or null.
     */
    private String upstream;

    /**
     * Status Pantera answers, or 0 when unknown.
     */
    private int status;

    /**
     * Machine-readable cause, or null.
     */
    private String reason;

    /**
     * Failure, or null when the cause is a plain upstream answer.
     */
    private Throwable error;

    /**
     * Ctor.
     * @param logger Logger name
     * @param action ECS event.action (snake_case)
     * @param headers Request headers (internal context headers give trace.id)
     */
    public ServerErrorLog(final String logger, final String action, final Headers headers) {
        this.logger = logger;
        this.action = action;
        this.headers = headers;
    }

    /**
     * @param name Repository name
     * @return this
     */
    public ServerErrorLog repository(final String name) {
        this.repository = name;
        return this;
    }

    /**
     * @param value Request path
     * @return this
     */
    public ServerErrorLog path(final String value) {
        this.path = value;
        return this;
    }

    /**
     * @param url Upstream URL the failed call went to (logged as url.full,
     *  its host as destination.address)
     * @return this
     */
    public ServerErrorLog upstream(final String url) {
        this.upstream = url;
        return this;
    }

    /**
     * @param code Status Pantera answers the client
     * @return this
     */
    public ServerErrorLog status(final int code) {
        this.status = code;
        return this;
    }

    /**
     * @param value Machine-readable cause (overrides the one derived from
     *  {@link #failure(Throwable)})
     * @return this
     */
    public ServerErrorLog reason(final String value) {
        this.reason = value;
        return this;
    }

    /**
     * The cause is an upstream answer (no throwable): reason
     * {@code upstream_status_<code>}.
     * @param code Upstream status
     * @return this
     */
    public ServerErrorLog upstreamStatus(final int code) {
        this.reason = "upstream_status_" + code;
        return this;
    }

    /**
     * @param err Failure behind the answer; also yields the reason when none
     *  was set explicitly
     * @return this
     */
    public ServerErrorLog failure(final Throwable err) {
        this.error = err;
        return this;
    }

    /**
     * Emit at DEBUG: expected per-request outcomes whose state transition
     * is logged elsewhere (an open circuit breaker, a full bulkhead).
     * @param message Human-readable description of what failed
     */
    public void debug(final String message) {
        this.emit(EcsLogger.debug(this.logger), message);
    }

    /**
     * Emit at WARN: upstream / transient causes.
     * @param message Human-readable description of what failed
     */
    public void warn(final String message) {
        this.emit(EcsLogger.warn(this.logger), message);
    }

    /**
     * Emit at WARN, or at DEBUG when the reason is an expected per-request
     * outcome whose state transition is logged elsewhere: {@code circuit_open}
     * (the outbound breaker logs its trip / probe / recovery) and
     * {@code bulkhead_full}. Use it where the reason is only known at run
     * time.
     * @param message Human-readable description of what failed
     */
    public void warnUnlessExpected(final String message) {
        final String cause = this.cause();
        if ("circuit_open".equals(cause) || "bulkhead_full".equals(cause)) {
            this.debug(message);
        } else {
            this.warn(message);
        }
    }

    /**
     * Emit at ERROR: unexpected internal failures.
     * @param message Human-readable description of what failed
     */
    public void error(final String message) {
        this.emit(EcsLogger.error(this.logger), message);
    }

    /**
     * Fill and emit the record.
     * @param log Level-bound logger
     * @param message Caller's description
     */
    private void emit(final EcsLogger log, final String message) {
        final String cause = this.cause();
        final StringBuilder text = new StringBuilder(message);
        if (this.status > 0) {
            text.append("; answering ").append(this.status);
        }
        text.append(" (cause: ").append(cause).append(')');
        log.message(text.toString())
            .eventCategory(this.upstream == null ? "web" : "network")
            .eventAction(this.action)
            .eventOutcome("failure")
            .field("event.reason", cause)
            .field("repository.name", this.repository)
            .field("url.path", this.path)
            .requestContext(this.headers)
            .field("log.source", "application");
        if (this.status > 0) {
            log.field("http.response.status_code", this.status);
        }
        if (this.upstream != null) {
            log.field("url.full", this.upstream);
            final String host = ServerErrorLog.host(this.upstream);
            if (host != null) {
                log.field("destination.address", host);
            }
        }
        if (this.error != null) {
            log.error(this.error);
        }
        log.log();
    }

    /**
     * Effective reason: the explicit one, else derived from the failure.
     * @return Reason token
     */
    private String cause() {
        final String cause;
        if (this.reason != null) {
            cause = this.reason;
        } else if (this.error != null) {
            cause = new FailureCause(this.error).reason();
        } else {
            cause = "unknown";
        }
        return cause;
    }

    /**
     * Host of a URL, or null when it does not parse.
     * @param url URL
     * @return Host
     */
    private static String host(final String url) {
        try {
            return URI.create(url).getHost();
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }
}
