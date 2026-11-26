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

import com.auto1.pantera.http.Headers;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RequestReasons}.
 *
 * @since 2.2.12
 */
final class RequestReasonsTest {

    @Test
    void keepsTheReasonNotedForASlot() {
        final RequestReasons reasons = new RequestReasons();
        final String slot = reasons.open();
        reasons.note(RequestReasonsTest.request(slot), "negative_cache");
        MatcherAssert.assertThat(
            reasons.close(slot), new IsEqual<>(Optional.of("negative_cache"))
        );
    }

    @Test
    void aFailureOutranksAMissAndARefusal() {
        // The remotes of one proxy: one answered 404, one was not asked, one
        // lost its connection. What explains the answer is the failure.
        final RequestReasons reasons = new RequestReasons();
        final String slot = reasons.open();
        final Headers request = RequestReasonsTest.request(slot);
        reasons.note(request, "upstream_status_404");
        reasons.note(request, "connection_closed");
        reasons.note(request, "circuit_open");
        MatcherAssert.assertThat(
            reasons.close(slot), new IsEqual<>(Optional.of("connection_closed"))
        );
    }

    @Test
    void aMemberThatWasNotAskedExplainsLeast() {
        // A group: one member was skipped by a routing rule, another said
        // "not found" from its upstream. The one that looked explains more.
        final RequestReasons reasons = new RequestReasons();
        final String slot = reasons.open();
        final Headers request = RequestReasonsTest.request(slot);
        reasons.note(request, "skipped_by_routing_rule");
        reasons.note(request, "upstream_status_404");
        reasons.note(request, "skipped_by_routing_rule");
        MatcherAssert.assertThat(
            reasons.close(slot), new IsEqual<>(Optional.of("upstream_status_404"))
        );
    }

    @Test
    void aSkipIsTheReasonWhenNobodyWasAsked() {
        final RequestReasons reasons = new RequestReasons();
        final String slot = reasons.open();
        reasons.note(RequestReasonsTest.request(slot), "skipped_by_routing_rule");
        MatcherAssert.assertThat(
            reasons.close(slot), new IsEqual<>(Optional.of("skipped_by_routing_rule"))
        );
    }

    @Test
    void aCachedNotFoundOutranksAnUpstreamNotFound() {
        // A group whose members all answered 404: it is a real "not found"
        // only if none of them answered from its negative cache.
        final RequestReasons reasons = new RequestReasons();
        final String slot = reasons.open();
        final Headers request = RequestReasonsTest.request(slot);
        reasons.note(request, "upstream_status_404");
        reasons.note(request, "negative_cache");
        reasons.note(request, "not_found");
        MatcherAssert.assertThat(
            reasons.close(slot), new IsEqual<>(Optional.of("negative_cache"))
        );
    }

    @Test
    void anUpstreamServerErrorIsAFailureAndAnUpstreamClientErrorIsNot() {
        final RequestReasons reasons = new RequestReasons();
        final String slot = reasons.open();
        final Headers request = RequestReasonsTest.request(slot);
        reasons.note(request, "upstream_status_409");
        reasons.note(request, "bulkhead_full");
        reasons.note(request, "upstream_status_503");
        MatcherAssert.assertThat(
            reasons.close(slot), new IsEqual<>(Optional.of("upstream_status_503"))
        );
    }

    @Test
    void closingASlotForgetsIt() {
        final RequestReasons reasons = new RequestReasons();
        final String slot = reasons.open();
        reasons.note(RequestReasonsTest.request(slot), "read_timeout");
        reasons.close(slot);
        MatcherAssert.assertThat(reasons.close(slot), new IsEqual<>(Optional.empty()));
    }

    @Test
    void aRequestWithoutASlotIsIgnored() {
        final RequestReasons reasons = new RequestReasons();
        final String slot = reasons.open();
        reasons.note(Headers.EMPTY, "read_timeout");
        MatcherAssert.assertThat(reasons.close(slot), new IsEqual<>(Optional.empty()));
    }

    @Test
    void everySlotHasItsOwnReason() {
        final RequestReasons reasons = new RequestReasons();
        final String one = reasons.open();
        final String two = reasons.open();
        reasons.note(RequestReasonsTest.request(one), "negative_cache");
        reasons.note(RequestReasonsTest.request(two), "connection_refused");
        MatcherAssert.assertThat(
            "the first request keeps its reason",
            reasons.close(one), new IsEqual<>(Optional.of("negative_cache"))
        );
        MatcherAssert.assertThat(
            "the second request keeps its own",
            reasons.close(two), new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    /**
     * Request headers carrying a slot.
     * @param slot Slot id
     * @return Headers
     */
    private static Headers request(final String slot) {
        return Headers.from(RequestReasons.SLOT_HEADER, slot);
    }
}
