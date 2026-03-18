/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.timeout;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

final class AutoBlockRegistryTest {

    private AutoBlockRegistry registry;

    @BeforeEach
    void setUp() {
        this.registry = new AutoBlockRegistry(new AutoBlockSettings(
            3, Duration.ofMillis(100), Duration.ofMinutes(60)
        ));
    }

    @Test
    void startsUnblocked() {
        assertThat(this.registry.isBlocked("remote-1"), is(false));
        assertThat(this.registry.status("remote-1"), equalTo("online"));
    }

    @Test
    void blocksAfterThresholdFailures() {
        this.registry.recordFailure("remote-1");
        this.registry.recordFailure("remote-1");
        assertThat(
            "Not blocked after 2",
            this.registry.isBlocked("remote-1"), is(false)
        );
        this.registry.recordFailure("remote-1");
        assertThat(
            "Blocked after 3",
            this.registry.isBlocked("remote-1"), is(true)
        );
        assertThat(this.registry.status("remote-1"), equalTo("blocked"));
    }

    @Test
    void unblocksAfterDuration() throws Exception {
        final AutoBlockRegistry fast = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMillis(50), Duration.ofMinutes(60)
        ));
        fast.recordFailure("remote-1");
        assertThat(fast.isBlocked("remote-1"), is(true));
        Thread.sleep(100);
        assertThat(fast.isBlocked("remote-1"), is(false));
        assertThat(fast.status("remote-1"), equalTo("probing"));
    }

    @Test
    void resetsOnSuccess() {
        this.registry.recordFailure("remote-1");
        this.registry.recordFailure("remote-1");
        this.registry.recordFailure("remote-1");
        assertThat(this.registry.isBlocked("remote-1"), is(true));
        this.registry.recordSuccess("remote-1");
        assertThat(this.registry.isBlocked("remote-1"), is(false));
        assertThat(this.registry.status("remote-1"), equalTo("online"));
    }

    @Test
    void usesFibonacciBackoff() throws Exception {
        final AutoBlockRegistry fast = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMillis(50), Duration.ofHours(1)
        ));
        // First block: 50ms (fib[0]=1)
        fast.recordFailure("r1");
        assertThat(fast.isBlocked("r1"), is(true));
        Thread.sleep(80);
        assertThat("Unblocked after first interval", fast.isBlocked("r1"), is(false));
        // Second block: 50ms (fib[1]=1, same duration)
        fast.recordFailure("r1");
        assertThat(fast.isBlocked("r1"), is(true));
        Thread.sleep(80);
        assertThat(
            "Unblocked after second interval", fast.isBlocked("r1"), is(false)
        );
        // Third block: 100ms (fib[2]=2)
        fast.recordFailure("r1");
        assertThat(fast.isBlocked("r1"), is(true));
        Thread.sleep(60);
        assertThat(
            "Still blocked during longer interval",
            fast.isBlocked("r1"), is(true)
        );
    }

    @Test
    void tracksMultipleRemotesIndependently() {
        this.registry.recordFailure("remote-a");
        this.registry.recordFailure("remote-a");
        this.registry.recordFailure("remote-a");
        assertThat(this.registry.isBlocked("remote-a"), is(true));
        assertThat(this.registry.isBlocked("remote-b"), is(false));
    }
}
