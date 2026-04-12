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
package com.auto1.pantera.group;

import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Member repository slice with circuit breaker delegating to {@link AutoBlockRegistry}.
 *
 * <p>Circuit breaker states (managed by the registry):
 * <ul>
 *   <li>ONLINE: Normal operation, requests pass through</li>
 *   <li>BLOCKED: Fast-fail mode, requests rejected immediately (after N failures)</li>
 *   <li>PROBING: Testing recovery, allow requests through</li>
 * </ul>
 *
 * @since 1.18.23
 */
public final class MemberSlice {

    /**
     * Member repository name.
     */
    private final String name;

    /**
     * Underlying slice for this member.
     */
    private final Slice delegate;

    /**
     * Auto-block registry for circuit breaker state.
     */
    private final AutoBlockRegistry registry;

    /**
     * Whether this member is a proxy repository (fetches from upstream).
     * Proxy members must always be queried on index miss because their
     * content is not pre-indexed — it only gets indexed after being cached.
     */
    private final boolean proxy;

    /**
     * Backward-compatible constructor (non-proxy).
     * Creates a local {@link AutoBlockRegistry} with default settings.
     *
     * @param name Member repository name
     * @param delegate Underlying slice
     */
    public MemberSlice(final String name, final Slice delegate) {
        this(name, delegate, new AutoBlockRegistry(AutoBlockSettings.defaults()), false);
    }

    /**
     * Constructor with proxy flag.
     *
     * @param name Member repository name
     * @param delegate Underlying slice
     * @param proxy Whether this member is a proxy repository
     */
    public MemberSlice(final String name, final Slice delegate, final boolean proxy) {
        this(name, delegate, new AutoBlockRegistry(AutoBlockSettings.defaults()), proxy);
    }

    /**
     * Constructor with shared registry (non-proxy).
     *
     * @param name Member repository name
     * @param delegate Underlying slice
     * @param registry Shared auto-block registry
     */
    public MemberSlice(final String name, final Slice delegate,
        final AutoBlockRegistry registry) {
        this(name, delegate, registry, false);
    }

    /**
     * Full constructor.
     *
     * @param name Member repository name
     * @param delegate Underlying slice
     * @param registry Shared auto-block registry
     * @param proxy Whether this member is a proxy repository
     */
    public MemberSlice(final String name, final Slice delegate,
        final AutoBlockRegistry registry, final boolean proxy) {
        this.name = Objects.requireNonNull(name, "name");
        this.delegate = delegate;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.proxy = proxy;
    }

    /**
     * Get member repository name.
     *
     * @return Member name
     */
    public String name() {
        return this.name;
    }

    /**
     * Get underlying slice.
     *
     * @return Delegate slice
     */
    public Slice slice() {
        return this.delegate;
    }

    /**
     * Whether this member is a proxy repository.
     * Proxy members fetch content from upstream registries on-demand.
     * Their content is only indexed after being cached, so they must
     * always be queried on an index miss.
     *
     * @return True if this member is a proxy
     */
    public boolean isProxy() {
        return this.proxy;
    }

    /**
     * Check if circuit breaker is in BLOCKED state.
     *
     * @return True if circuit is open (fast-failing)
     */
    public boolean isCircuitOpen() {
        return this.registry.isBlocked(this.name);
    }

    /**
     * Record successful response from this member.
     * Resets circuit breaker state via registry.
     */
    public void recordSuccess() {
        this.registry.recordSuccess(this.name);
    }

    /**
     * Record failed response from this member.
     * May block the remote via registry if threshold exceeded.
     */
    public void recordFailure() {
        this.registry.recordFailure(this.name);
    }

    /**
     * Rewrite request path to include member repository name.
     *
     * <p>Transforms: /path -> /member/path
     *
     * @param original Original request line
     * @return Rewritten request line with member prefix
     */
    public RequestLine rewritePath(final RequestLine original) {
        final URI uri = original.uri();
        final String raw = uri.getRawPath();
        final String base = raw.startsWith("/") ? raw : "/" + raw;
        final String prefix = "/" + this.name + "/";

        // Avoid double-prefixing
        final String path = base.startsWith(prefix) ? base : ("/" + this.name + base);

        final StringBuilder full = new StringBuilder(path);
        if (uri.getRawQuery() != null) {
            full.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            full.append('#').append(uri.getRawFragment());
        }

        final RequestLine result = new RequestLine(
            original.method().value(),
            full.toString(),
            original.version()
        );

        EcsLogger.debug("com.auto1.pantera.group")
            .message(String.format("MemberSlice '%s' rewritePath: %s to %s", this.name, raw, result.uri().getPath()))
            .eventCategory("repository")
            .eventAction("path_rewrite")
            .log();

        return result;
    }

    /**
     * Get circuit breaker state for monitoring.
     *
     * @return "ONLINE", "BLOCKED", or "PROBING"
     */
    public String circuitState() {
        return this.registry.status(this.name).toUpperCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return String.format(
            "MemberSlice{name=%s, proxy=%s, circuit=%s}",
            this.name,
            this.proxy,
            circuitState()
        );
    }
}
