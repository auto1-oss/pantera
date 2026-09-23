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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Test-only system resolver that records every name the JDK hands to the
 * platform resolver, then delegates to the built-in one. Installed for the
 * whole test JVM through {@code META-INF/services}; it lets a test prove a
 * string never reached DNS.
 *
 * @since 2.2.9
 */
public final class RecordingResolverProvider extends InetAddressResolverProvider {

    /**
     * Name the provider answers itself, so a test can check it is installed.
     */
    static final String PROBE = "recording-resolver-probe.invalid";

    /**
     * Names looked up through the system resolver since JVM start.
     */
    static final Set<String> LOOKUPS = ConcurrentHashMap.newKeySet();

    @Override
    public InetAddressResolver get(final Configuration configuration) {
        final InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(
                final String host, final LookupPolicy policy
            ) throws UnknownHostException {
                RecordingResolverProvider.LOOKUPS.add(host);
                final Stream<InetAddress> result;
                if (RecordingResolverProvider.PROBE.equals(host)) {
                    result = Stream.of(InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
                } else {
                    result = builtin.lookupByName(host, policy);
                }
                return result;
            }

            @Override
            public String lookupByAddress(final byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "pantera-test-recording-resolver";
    }
}
