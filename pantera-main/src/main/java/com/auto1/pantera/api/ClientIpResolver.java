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
package com.auto1.pantera.api;

/**
 * Resolves the client address recorded in logs and audit events.
 *
 * <p>{@code X-Forwarded-For} / {@code X-Real-IP} are client-supplied
 * headers; honouring them unconditionally let any caller falsify the
 * {@code client.ip} persisted with its own audited actions (the bundled
 * nginx appends rather than replaces, so the spoof survived the proxy
 * too). They are honoured only when the deployment declares a trusted
 * reverse proxy in front of Pantera — the same
 * {@code trust_forwarded_headers} setting that governs the client-facing
 * base URL — and otherwise the TCP peer address is recorded.</p>
 *
 * @since 2.2.9
 */
public final class ClientIpResolver {

    /**
     * Whether a trusted proxy fronts Pantera.
     */
    private final boolean trustForwarded;

    /**
     * Ctor.
     *
     * @param trustForwarded Honour forwarding headers
     */
    public ClientIpResolver(final boolean trustForwarded) {
        this.trustForwarded = trustForwarded;
    }

    /**
     * Resolve the client address.
     *
     * @param peer TCP peer address (nullable)
     * @param forwardedFor {@code X-Forwarded-For} header value (nullable)
     * @param realIp {@code X-Real-IP} header value (nullable)
     * @return Address to record, or {@code null} when nothing is known
     */
    public String resolve(final String peer, final String forwardedFor, final String realIp) {
        if (this.trustForwarded) {
            String forwarded = forwardedFor;
            if (forwarded != null && forwarded.contains(",")) {
                forwarded = forwarded.substring(0, forwarded.indexOf(',')).trim();
            }
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.trim();
            }
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return peer == null || peer.isBlank() ? null : peer;
    }
}
