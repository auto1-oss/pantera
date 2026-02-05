/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.proxy;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AutoBlockService}.
 */
class AutoBlockServiceTest {

    @Test
    void startsInClosedState() {
        final AutoBlockService service = new AutoBlockService();
        assertFalse(service.isBlocked("https://example.com"));
        assertEquals(AutoBlockService.State.CLOSED, service.getState("https://example.com"));
    }

    @Test
    void blocksAfterFailureThresholdExceeded() {
        final AutoBlockService service = new AutoBlockService(
            3,  // threshold
            Duration.ofMinutes(1),  // window
            Duration.ofMinutes(5)   // block duration
        );

        final String upstream = "https://registry.npmjs.org";

        // Record failures below threshold
        service.recordFailure(upstream, new ConnectException("fail 1"));
        service.recordFailure(upstream, new ConnectException("fail 2"));
        assertFalse(service.isBlocked(upstream), "Should not block before threshold");

        // Exceed threshold
        service.recordFailure(upstream, new ConnectException("fail 3"));
        assertTrue(service.isBlocked(upstream), "Should block after threshold exceeded");
        assertEquals(AutoBlockService.State.OPEN, service.getState(upstream));
    }

    @Test
    void successResetsFailureCount() {
        final AutoBlockService service = new AutoBlockService(
            3,
            Duration.ofMinutes(1),
            Duration.ofMinutes(5)
        );

        final String upstream = "https://registry.npmjs.org";

        // Record some failures
        service.recordFailure(upstream, new ConnectException("fail 1"));
        service.recordFailure(upstream, new ConnectException("fail 2"));
        assertEquals(2, service.failureCount(upstream));

        // Success resets count
        service.recordSuccess(upstream);
        assertEquals(0, service.failureCount(upstream));
        assertFalse(service.isBlocked(upstream));
    }

    @Test
    void transitionsToHalfOpenAfterBlockExpires() throws InterruptedException {
        final AutoBlockService service = new AutoBlockService(
            2,  // threshold
            Duration.ofMinutes(1),
            Duration.ofMillis(100)  // Short block for test
        );

        final String upstream = "https://registry.npmjs.org";

        // Trigger block
        service.recordFailure(upstream, new ConnectException("fail"));
        service.recordFailure(upstream, new ConnectException("fail"));
        assertTrue(service.isBlocked(upstream));

        // Wait for block to expire
        Thread.sleep(150);

        // Should transition to half-open
        assertEquals(AutoBlockService.State.HALF_OPEN, service.getState(upstream));
        assertFalse(service.isBlocked(upstream));
    }

    @Test
    void halfOpenClosesOnSuccess() throws InterruptedException {
        final AutoBlockService service = new AutoBlockService(
            2,
            Duration.ofMinutes(1),
            Duration.ofMillis(50)
        );

        final String upstream = "https://registry.npmjs.org";

        // Trigger block
        service.recordFailure(upstream, new ConnectException("fail"));
        service.recordFailure(upstream, new ConnectException("fail"));

        // Wait for half-open
        Thread.sleep(100);
        assertEquals(AutoBlockService.State.HALF_OPEN, service.getState(upstream));

        // Success in half-open closes circuit
        service.recordSuccess(upstream);
        assertEquals(AutoBlockService.State.CLOSED, service.getState(upstream));
        assertFalse(service.isBlocked(upstream));
    }

    @Test
    void halfOpenReopensOnFailure() throws InterruptedException {
        final AutoBlockService service = new AutoBlockService(
            2,
            Duration.ofMinutes(1),
            Duration.ofMillis(50)
        );

        final String upstream = "https://registry.npmjs.org";

        // Trigger block
        service.recordFailure(upstream, new ConnectException("fail"));
        service.recordFailure(upstream, new ConnectException("fail"));

        // Wait for half-open
        Thread.sleep(100);
        assertEquals(AutoBlockService.State.HALF_OPEN, service.getState(upstream));

        // Failure in half-open reopens circuit
        service.recordFailure(upstream, new ConnectException("fail again"));
        assertEquals(AutoBlockService.State.OPEN, service.getState(upstream));
        assertTrue(service.isBlocked(upstream));
    }

    @Test
    void windowResetsFailureCount() throws InterruptedException {
        final AutoBlockService service = new AutoBlockService(
            3,
            Duration.ofMillis(100),  // Short window
            Duration.ofMinutes(5)
        );

        final String upstream = "https://registry.npmjs.org";

        // Record failures
        service.recordFailure(upstream, new ConnectException("fail"));
        service.recordFailure(upstream, new ConnectException("fail"));
        assertEquals(2, service.failureCount(upstream));

        // Wait for window to expire
        Thread.sleep(150);

        // Failure count should reset to 0 (on next check)
        assertEquals(0, service.failureCount(upstream));

        // New failure starts fresh count
        service.recordFailure(upstream, new ConnectException("fail"));
        assertEquals(1, service.failureCount(upstream));
    }

    @Test
    void resetClearsState() {
        final AutoBlockService service = new AutoBlockService(
            2,
            Duration.ofMinutes(1),
            Duration.ofMinutes(5)
        );

        final String upstream = "https://registry.npmjs.org";

        // Trigger block
        service.recordFailure(upstream, new ConnectException("fail"));
        service.recordFailure(upstream, new ConnectException("fail"));
        assertTrue(service.isBlocked(upstream));

        // Manual reset
        service.reset(upstream);
        assertFalse(service.isBlocked(upstream));
        assertEquals(AutoBlockService.State.CLOSED, service.getState(upstream));
    }

    @Test
    void remainingBlockTimeCalculation() throws InterruptedException {
        final AutoBlockService service = new AutoBlockService(
            2,
            Duration.ofMinutes(1),
            Duration.ofMillis(500)
        );

        final String upstream = "https://registry.npmjs.org";

        // Trigger block
        service.recordFailure(upstream, new ConnectException("fail"));
        service.recordFailure(upstream, new ConnectException("fail"));

        // Check remaining time (should be ~500ms)
        final Duration remaining = service.remainingBlockTime(upstream);
        assertTrue(remaining.toMillis() > 0 && remaining.toMillis() <= 500,
            "Remaining time should be positive: " + remaining.toMillis());

        // Wait a bit
        Thread.sleep(200);

        // Remaining time should decrease
        final Duration remaining2 = service.remainingBlockTime(upstream);
        assertTrue(remaining2.toMillis() < remaining.toMillis(),
            "Remaining time should decrease");
    }

    @Test
    void independentUpstreams() {
        final AutoBlockService service = new AutoBlockService(
            2,
            Duration.ofMinutes(1),
            Duration.ofMinutes(5)
        );

        final String upstream1 = "https://registry.npmjs.org";
        final String upstream2 = "https://pypi.org";

        // Block upstream1
        service.recordFailure(upstream1, new ConnectException("fail"));
        service.recordFailure(upstream1, new ConnectException("fail"));
        assertTrue(service.isBlocked(upstream1));

        // upstream2 should not be affected
        assertFalse(service.isBlocked(upstream2));

        // Failures on upstream2 don't affect upstream1
        service.recordFailure(upstream2, new ConnectException("fail"));
        assertEquals(1, service.failureCount(upstream2));
        assertTrue(service.isBlocked(upstream1));
    }

    @Test
    void allStatesReturnsAllTrackedUpstreams() {
        final AutoBlockService service = new AutoBlockService(
            2,
            Duration.ofMinutes(1),
            Duration.ofMinutes(5)
        );

        service.recordFailure("upstream1", new ConnectException("fail"));
        service.recordFailure("upstream2", new ConnectException("fail"));
        service.recordFailure("upstream2", new ConnectException("fail"));

        final var states = service.allStates();
        assertEquals(2, states.size());
        assertEquals(AutoBlockService.State.CLOSED, states.get("upstream1"));
        assertEquals(AutoBlockService.State.OPEN, states.get("upstream2"));
    }

    @Test
    void validatesThreshold() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new AutoBlockService(0, Duration.ofMinutes(1), Duration.ofMinutes(5))
        );
    }
}
