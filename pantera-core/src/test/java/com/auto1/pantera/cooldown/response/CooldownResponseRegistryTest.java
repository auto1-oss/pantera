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
package com.auto1.pantera.cooldown.response;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests for {@link CooldownResponseRegistry}.
 *
 * @since 2.2.0
 */
final class CooldownResponseRegistryTest {

    private CooldownResponseRegistry registry;

    @BeforeEach
    void setUp() {
        this.registry = CooldownResponseRegistry.instance();
        this.registry.clear();
    }

    @Test
    void registersAndRetrievesByRepoType() {
        final CooldownResponseFactory factory = new StubFactory("maven");
        this.registry.register("maven", factory);
        assertThat(this.registry.get("maven"), is(sameInstance(factory)));
    }

    @Test
    void returnsNullForUnregisteredType() {
        assertThat(this.registry.get("unknown"), is(nullValue()));
    }

    @Test
    void registersFactoryWithAliases() {
        final CooldownResponseFactory factory = new StubFactory("maven");
        this.registry.register(factory, "gradle");
        assertThat(this.registry.get("maven"), is(sameInstance(factory)));
        assertThat(this.registry.get("gradle"), is(sameInstance(factory)));
    }

    @Test
    void gradleAliasReusesMavenFactory() {
        final CooldownResponseFactory maven = new StubFactory("maven");
        this.registry.register(maven, "gradle");
        final CooldownResponseFactory resolved = this.registry.get("gradle");
        assertThat(resolved, is(notNullValue()));
        assertThat(resolved.repoType(), equalTo("maven"));
    }

    @Test
    void registeredTypesIncludesAliases() {
        this.registry.register(new StubFactory("maven"), "gradle");
        this.registry.register(new StubFactory("npm"));
        assertThat(
            this.registry.registeredTypes(),
            containsInAnyOrder("maven", "gradle", "npm")
        );
    }

    /**
     * Stub factory for testing registry wiring.
     */
    private static final class StubFactory implements CooldownResponseFactory {
        private final String type;

        StubFactory(final String type) {
            this.type = type;
        }

        @Override
        public Response forbidden(final CooldownBlock block) {
            return ResponseBuilder.forbidden()
                .textBody("blocked")
                .build();
        }

        @Override
        public String repoType() {
            return this.type;
        }
    }
}
