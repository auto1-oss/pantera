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
package com.auto1.pantera.http.misc;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HttpMethodMeterFilter}.
 *
 * @since 2.2.12
 */
final class HttpMethodMeterFilterTest {

    @Test
    void keepsAStandardMethod() {
        MatcherAssert.assertThat(
            new HttpMethodMeterFilter().map(HttpMethodMeterFilterTest.id("GET")).getTag("method"),
            new IsEqual<>("GET")
        );
    }

    @Test
    void replacesAMethodNobodyDefined() {
        MatcherAssert.assertThat(
            new HttpMethodMeterFilter().map(HttpMethodMeterFilterTest.id("ZZTOP")).getTag("method"),
            new IsEqual<>("_other")
        );
    }

    @Test
    void keepsTheOtherTagsOfTheMeter() {
        MatcherAssert.assertThat(
            new HttpMethodMeterFilter().map(HttpMethodMeterFilterTest.id("ZZTOP")).getTag("code"),
            new IsEqual<>("200")
        );
    }

    @Test
    void leavesAMeterWithoutAMethodAlone() {
        final Meter.Id id = new Meter.Id(
            "pantera.cache.hits", Tags.of("repo_name", "maven"), null, null, Meter.Type.COUNTER
        );
        MatcherAssert.assertThat(new HttpMethodMeterFilter().map(id), new IsEqual<>(id));
    }

    @Test
    void anyNumberOfMadeUpMethodsIsOneSeries() {
        // What an attacker can do without credentials: one request per
        // made-up method token. The registry must not grow with them.
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new HttpMethodMeterFilter());
        for (int idx = 0; idx < 1000; idx += 1) {
            registry.counter("vertx.http.server.requests", "method", "ZZ" + idx).increment();
        }
        MatcherAssert.assertThat(registry.getMeters().size(), new IsEqual<>(1));
    }

    /**
     * Id of a request meter.
     * @param method Method tag value
     * @return Id
     */
    private static Meter.Id id(final String method) {
        return new Meter.Id(
            "vertx.http.server.requests", Tags.of("method", method, "code", "200"),
            null, null, Meter.Type.COUNTER
        );
    }
}
