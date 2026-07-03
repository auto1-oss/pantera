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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link CooldownResponseRegistry#getOrThrow(String)}.
 *
 * @since 2.2.0
 */
final class CooldownResponseRegistryGetOrThrowTest {

    private CooldownResponseRegistry registry;

    @BeforeEach
    void setUp() {
        this.registry = CooldownResponseRegistry.instance();
        this.registry.clear();
    }

    @Test
    void getOrThrow_returnsRegisteredFactory() {
        final CooldownResponseFactory factory = new StubFactory("foo");
        this.registry.register("foo", factory);
        assertThat(this.registry.getOrThrow("foo"), is(sameInstance(factory)));
    }

    @Test
    void getOrThrow_throwsWhenMissing() {
        final IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> this.registry.getOrThrow("bar")
        );
        assertThat(ex.getMessage(), containsString("bar"));
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
