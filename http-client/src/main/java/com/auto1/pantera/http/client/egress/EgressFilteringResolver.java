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
import java.util.function.Supplier;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.Origin;
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
 * <p>When the connection goes through an outbound HTTP proxy, Jetty asks
 * this resolver only for the PROXY's address; the real target is read
 * from the {@link Destination} in the resolution context and checked by
 * name and by its own resolution before the proxy is resolved. Every
 * redirect hop to a new origin opens a new destination, so hops are
 * covered the same way.</p>
 *
 * @since 2.2.9
 */
public final class EgressFilteringResolver implements SocketAddressResolver {

    /**
     * Policy to apply.
     */
    private final Supplier<EgressPolicy> policy;

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
        this(() -> policy, delegate);
    }

    /**
     * Ctor reading the policy through a supplier on every resolve, so an
     * admin edit applies to the next outbound connect without a restart.
     *
     * @param policy Live policy source
     * @param delegate Real resolver
     */
    public EgressFilteringResolver(
        final Supplier<EgressPolicy> policy, final SocketAddressResolver delegate
    ) {
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
        final EgressPolicy current = this.policy.get();
        final Object dest = context == null ? null : context.get(Destination.CONTEXT_KEY);
        if (dest instanceof Destination && ((Destination) dest).getProxy() != null) {
            final Origin.Address target = ((Destination) dest).getOrigin().getAddress();
            this.checkProxiedTarget(
                current, target.getHost(), target.getPort(), context,
                () -> this.resolveDirect(current, host, port, context, promise),
                promise
            );
        } else {
            this.resolveDirect(current, host, port, context, promise);
        }
    }

    /**
     * Check the real request target of a connection that goes through an
     * outbound HTTP proxy. Jetty resolves only the proxy's address then, so
     * without this the target — and every redirect hop, each of which gets
     * its own destination and connection — would never meet the policy.
     * The proxy connects to whichever address IT resolves, so the target
     * passes only if none of the addresses Pantera sees is denied. A target
     * Pantera's own DNS cannot resolve (proxy-only DNS is common behind an
     * egress proxy) is left to the proxy: Pantera has no address to judge.
     *
     * @param current Policy snapshot
     * @param target Target host
     * @param port Target port
     * @param context Jetty resolution context
     * @param proceed Continues with the proxy's own resolution
     * @param promise Promise to fail on denial
     */
    private void checkProxiedTarget(
        final EgressPolicy current,
        final String target,
        final int port,
        final Map<String, Object> context,
        final Runnable proceed,
        final Promise<List<InetSocketAddress>> promise
    ) {
        final Optional<String> byName = current.hostRejection(target);
        if (byName.isPresent()) {
            promise.failed(this.deny(target, port, byName.get()));
            return;
        }
        this.delegate.resolve(target, port, context, new Promise<>() {
            @Override
            public void succeeded(final List<InetSocketAddress> resolved) {
                final Optional<String> reason = resolved.stream()
                    .filter(address -> address.getAddress() != null)
                    .map(address -> current.rejection(target, address.getAddress()))
                    .flatMap(Optional::stream)
                    .findFirst();
                if (reason.isPresent()) {
                    promise.failed(EgressFilteringResolver.this.deny(target, port, reason.get()));
                } else {
                    proceed.run();
                }
            }

            @Override
            public void failed(final Throwable failure) {
                proceed.run();
            }
        });
    }

    /**
     * Resolve {@code host} (the target, or the proxy) and keep only the
     * addresses the policy allows.
     *
     * @param current Policy snapshot
     * @param host Host to connect to
     * @param port Port
     * @param context Jetty resolution context
     * @param promise Resolution promise
     */
    private void resolveDirect(
        final EgressPolicy current,
        final String host,
        final int port,
        final Map<String, Object> context,
        final Promise<List<InetSocketAddress>> promise
    ) {
        final Optional<String> byName = current.hostRejection(host);
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
                        : current.rejection(host, address.getAddress());
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
