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

import java.util.Set;

/**
 * The reason of a non-2xx answer that nothing explained more precisely: a
 * name for its status. Seeing one of these on an access record means that
 * the code which produced the answer did not say why.
 *
 * @since 2.2.12
 */
public final class StatusReason {

    /**
     * Every token this class produces.
     */
    static final Set<String> TOKENS = Set.of(
        "bad_request", "authentication_required", "forbidden", "not_found",
        "method_not_allowed", "conflict", "precondition_failed", "payload_too_large",
        "range_not_satisfiable", "rate_limited", "client_error", "server_error",
        "bad_gateway", "service_unavailable", "gateway_timeout"
    );

    /**
     * HTTP status code.
     */
    private final int code;

    /**
     * Ctor.
     * @param code HTTP status code
     */
    public StatusReason(final int code) {
        this.code = code;
    }

    /**
     * Reason token for the status.
     * @return Token
     */
    public String token() {
        final String token;
        switch (this.code) {
            case 400 -> token = "bad_request";
            case 401 -> token = "authentication_required";
            case 403 -> token = "forbidden";
            case 404 -> token = "not_found";
            case 405 -> token = "method_not_allowed";
            case 409 -> token = "conflict";
            case 412 -> token = "precondition_failed";
            case 413 -> token = "payload_too_large";
            case 416 -> token = "range_not_satisfiable";
            case 429 -> token = "rate_limited";
            case 502 -> token = "bad_gateway";
            case 503 -> token = "service_unavailable";
            case 504 -> token = "gateway_timeout";
            default -> token = this.code >= 500 ? "server_error" : "client_error";
        }
        return token;
    }
}
