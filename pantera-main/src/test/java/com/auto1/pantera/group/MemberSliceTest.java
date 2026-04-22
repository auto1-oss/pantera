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

import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

final class MemberSliceTest {

    @Test
    void reportsOpenCircuitFromRegistry() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 1, 30, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        assertThat(member.isCircuitOpen(), is(false));
        registry.recordFailure("test-member");
        assertThat(member.isCircuitOpen(), is(true));
    }

    @Test
    void recordsSuccessViaRegistry() throws Exception {
        // Short block window so the test can wait out the block and
        // exercise the PROBING → ONLINE transition on recordSuccess.
        // Under the 2.2.0 rate-based design, a stray recordSuccess in
        // BLOCKED state does NOT immediately close the circuit — the
        // block window must expire first, then the next success in
        // PROBING state closes it. This matches industry behaviour
        // (Hystrix, Resilience4j) and is what makes Fibonacci back-off
        // useful under genuinely broken upstreams.
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 1, 30, Duration.ofMillis(50), Duration.ofSeconds(30)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        registry.recordFailure("test-member");
        assertThat(member.isCircuitOpen(), is(true));
        // Wait for block to expire → PROBING state.
        Thread.sleep(80);
        assertThat("block expired, now probing", member.isCircuitOpen(), is(false));
        // Probe success in PROBING → CLOSED.
        member.recordSuccess();
        assertThat(member.isCircuitOpen(), is(false));
    }

    @Test
    void recordsFailureViaRegistry() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 2, 30, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        member.recordFailure();
        assertThat(member.isCircuitOpen(), is(false));
        member.recordFailure();
        assertThat(member.isCircuitOpen(), is(true));
    }

    @Test
    void reportsCircuitState() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 1, 30, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        assertThat(member.circuitState(), equalTo("ONLINE"));
        registry.recordFailure("test-member");
        assertThat(member.circuitState(), equalTo("BLOCKED"));
    }

    @Test
    void sharedRegistryAcrossGroupsReducesSignalFragmentation() {
        // One shared registry for "maven-central" — simulates RepositorySlices.getOrCreateMemberRegistry
        final AutoBlockRegistry sharedRegistry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 2, 30, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));

        // Two groups both containing "maven-central" — each wraps the same shared registry
        final MemberSlice libsRelease = new MemberSlice("maven-central", null, sharedRegistry);
        final MemberSlice libsSnapshot = new MemberSlice("maven-central", null, sharedRegistry);

        // Initially both views agree: circuit is ONLINE
        assertThat(libsRelease.isCircuitOpen(), is(false));
        assertThat(libsSnapshot.isCircuitOpen(), is(false));

        // Cause N failures through libs-release (threshold = 2)
        libsRelease.recordFailure();
        assertThat(libsRelease.isCircuitOpen(), is(false));
        libsRelease.recordFailure();

        // libs-release has now tripped the circuit
        assertThat(libsRelease.isCircuitOpen(), is(true));

        // libs-snapshot immediately sees the same BLOCKED state — no extra failures needed
        assertThat(libsSnapshot.isCircuitOpen(), is(true));
    }
}
