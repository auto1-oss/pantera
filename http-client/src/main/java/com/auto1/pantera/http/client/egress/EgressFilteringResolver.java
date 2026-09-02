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
package com.auto1.pantera.http.client.egress;

import com.auto1.pantera.http.log.EcsLogger;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;

/**
 * Jetty {@link SocketAddressResolver} that applies the {@link EgressPolicy}
 * AFTER DNS resolution. Installed on the shared outbound {@code HttpClient},
 * so it guards every connect Pantera makes — including each redirect hop —
 * and the DNS-rebinding shape of SSRF (a benign-looking hostname that
 * resolves into a denied range). A name-level denial short-circuits before
 * any lookup.
 *
 * @since 2.2.9
 */
public final class EgressFilteringResolver implements SocketAddressResolver {

    /**
     * Policy to apply.
     */
    private final EgressPolicy policy;

    /**
     * Real resolver (Jetty's async resolver in production).
     */
    private final SocketAddressResolver delegate;

    /**
     * Ctor.
     *
     * @param policy Egress policy
     * @param delegate Underlying resolver
     */
    public EgressFilteringResolver(final EgressPolicy policy, final SocketAddressResolver delegate) {
        this.policy = policy;
        this.delegate = delegate;
    }

    @Override
    public void resolve(
        final String host,
        final int port,
        final Map<String, Object> context,
        final Promise<List<InetSocketAddress>> promise
    ) {
        final Optional<String> byName = this.policy.hostRejection(host);
        if (byName.isPresent()) {
            promise.failed(this.deny(host, port, byName.get()));
            return;
        }
        this.delegate.resolve(host, port, context, new Promise<>() {
            @Override
            public void succeeded(final List<InetSocketAddress> resolved) {
                final List<InetSocketAddress> kept = new ArrayList<>(resolved.size());
                String reason = null;
                for (final InetSocketAddress address : resolved) {
                    final Optional<String> rejection = address.getAddress() == null
                        ? Optional.of("unresolved address")
                        : EgressFilteringResolver.this.policy.rejection(host, address.getAddress());
                    if (rejection.isPresent()) {
                        reason = rejection.get();
                    } else {
                        kept.add(address);
                    }
                }
                if (kept.isEmpty()) {
                    promise.failed(EgressFilteringResolver.this.deny(
                        host, port, reason == null ? "no address" : reason
                    ));
                } else {
                    promise.succeeded(kept);
                }
            }

            @Override
            public void failed(final Throwable failure) {
                promise.failed(failure);
            }
        });
    }

    /**
     * Build (and log) the denial.
     *
     * @param host Host
     * @param port Port
     * @param reason Policy reason
     * @return Exception to fail the resolution with
     */
    private EgressDeniedException deny(final String host, final int port, final String reason) {
        EcsLogger.warn("com.auto1.pantera.http.client")
            .message("Outbound request denied by egress policy: " + reason)
            .eventCategory("network")
            .eventAction("egress_denied")
            .eventOutcome("failure")
            .field("destination.address", host)
            .field("destination.port", port)
            .field("event.reason", reason)
            .field("log.source", "application")
            .log();
        return new EgressDeniedException(host, reason);
    }
}
