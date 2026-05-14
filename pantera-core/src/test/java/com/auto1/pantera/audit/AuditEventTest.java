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
package com.auto1.pantera.audit;

import java.time.Instant;
import java.util.Map;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Unit tests for {@link AuditEvent}. Pin the record contract — required
 * fields, null defaults for the optional ones, and the builder helpers.
 *
 * @since 2.2.0
 */
final class AuditEventTest {

    @Test
    @DisplayName("success() builder produces a successful event with empty details")
    void successBuilder() {
        final AuditEvent event = AuditEvent.success(
            "alice", "COOLDOWN_UNBLOCK", "maven-central"
        );
        assertThat(
            "reason: actor preserved",
            event.actor(), new IsEqual<>("alice")
        );
        assertThat(
            "reason: action preserved",
            event.action(), new IsEqual<>("COOLDOWN_UNBLOCK")
        );
        assertThat(
            "reason: target preserved",
            event.target(), new IsEqual<>("maven-central")
        );
        assertThat(
            "reason: success flag true",
            event.success(), new IsEqual<>(true)
        );
        assertThat(
            "reason: details is empty map (not null)",
            event.details().isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    @DisplayName("failure() builder records the reason in details")
    void failureBuilder() {
        final AuditEvent event = AuditEvent.failure(
            "bob", "REPO_DELETE", "internal-repo", "insufficient_permissions"
        );
        assertThat(
            "reason: success flag false",
            event.success(), new IsEqual<>(false)
        );
        assertThat(
            "reason: failure reason recorded",
            event.details().get("reason"),
            new IsEqual<>("insufficient_permissions")
        );
    }

    @Test
    @DisplayName("null actor → NPE")
    void nullActorRejected() {
        try {
            new AuditEvent(Instant.now(), null, "X", "t", Map.of(), true, null);
        } catch (final NullPointerException ex) {
            return;
        }
        throw new AssertionError("expected NullPointerException for null actor");
    }

    @Test
    @DisplayName("empty action → IllegalArgumentException")
    void emptyActionRejected() {
        try {
            new AuditEvent(Instant.now(), "a", "", "t", Map.of(), true, null);
        } catch (final IllegalArgumentException ex) {
            return;
        }
        throw new AssertionError(
            "expected IllegalArgumentException for empty action"
        );
    }

    @Test
    @DisplayName("null details defaults to empty map")
    void nullDetailsDefaultsToEmptyMap() {
        final AuditEvent event = new AuditEvent(
            Instant.now(), "alice", "X", null, null, true, null
        );
        assertThat(event.details().isEmpty(), new IsEqual<>(true));
    }

    @Test
    @DisplayName("withIpAddress() returns a copy with the IP set")
    void withIpAddressReturnsCopy() {
        final AuditEvent base = AuditEvent.success(
            "alice", "X", "y"
        );
        final AuditEvent withIp = base.withIpAddress("203.0.113.1");
        assertThat(
            "reason: new instance has the IP",
            withIp.ipAddress(), new IsEqual<>("203.0.113.1")
        );
        assertThat(
            "reason: original is unchanged",
            base.ipAddress(), new IsEqual<>(null)
        );
        assertThat(
            "reason: action preserved across copy",
            withIp.action(), new IsEqual<>(base.action())
        );
    }

    @Test
    @DisplayName("details map is defensively copied — caller mutation invisible")
    void detailsMapIsDefensivelyCopied() {
        final java.util.Map<String, Object> caller = new java.util.HashMap<>();
        caller.put("k", "v");
        final AuditEvent event = new AuditEvent(
            Instant.now(), "alice", "X", "y", caller, true, null
        );
        caller.put("k", "mutated");
        assertThat(
            "reason: event's view did not see the post-construction mutation",
            event.details().get("k"), new IsEqual<>("v")
        );
    }
}
