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
package com.auto1.pantera.cooldown.config;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRequestDetector;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link CooldownAdapterRegistry}.
 *
 * <p>Verifies bundle registration, lookup by type, alias registration,
 * and behaviour for missing types.</p>
 *
 * @since 2.2.0
 */
final class CooldownAdapterRegistryTest {

    private CooldownAdapterRegistry registry;

    @BeforeEach
    void setUp() {
        this.registry = CooldownAdapterRegistry.instance();
        this.registry.clear();
    }

    @Test
    void registerAndRetrieveBundle() {
        final CooldownAdapterBundle<List<String>> bundle = goBundle();
        this.registry.register("go", bundle);

        final Optional<CooldownAdapterBundle<?>> found = this.registry.get("go");
        assertThat("Bundle should be found", found.isPresent(), is(true));
        assertThat("Parser should match", found.get().parser(), is(bundle.parser()));
        assertThat("Filter should match", found.get().filter(), is(bundle.filter()));
        assertThat("Rewriter should match", found.get().rewriter(), is(bundle.rewriter()));
        assertThat("Detector should match", found.get().detector(), is(bundle.detector()));
        assertThat("ResponseFactory should match",
            found.get().responseFactory(), is(bundle.responseFactory()));
    }

    @Test
    void missingTypeReturnsEmpty() {
        final Optional<CooldownAdapterBundle<?>> found = this.registry.get("nonexistent");
        assertThat("Missing type returns empty", found.isPresent(), is(false));
    }

    @Test
    void registerWithAliases() {
        final CooldownAdapterBundle<List<String>> bundle = goBundle();
        this.registry.register("maven", bundle, "gradle");

        assertThat("Primary type resolved",
            this.registry.get("maven").isPresent(), is(true));
        assertThat("Alias resolved",
            this.registry.get("gradle").isPresent(), is(true));
        assertThat("Alias maps to same bundle",
            this.registry.get("gradle").get().parser(),
            is(this.registry.get("maven").get().parser()));
    }

    @Test
    void registeredTypesIncludesAllTypesAndAliases() {
        this.registry.register("go", goBundle());
        this.registry.register("maven", goBundle(), "gradle");
        this.registry.register("npm", goBundle());

        assertThat(this.registry.registeredTypes(),
            containsInAnyOrder("go", "maven", "gradle", "npm"));
    }

    @Test
    void clearRemovesAll() {
        this.registry.register("go", goBundle());
        this.registry.register("npm", goBundle());
        this.registry.clear();

        assertThat("After clear, go should be absent",
            this.registry.get("go").isPresent(), is(false));
        assertThat("After clear, npm should be absent",
            this.registry.get("npm").isPresent(), is(false));
        assertThat("After clear, registered types should be empty",
            this.registry.registeredTypes().isEmpty(), is(true));
    }

    @Test
    void overwriteExistingType() {
        final CooldownAdapterBundle<List<String>> bundle1 = goBundle();
        final CooldownAdapterBundle<List<String>> bundle2 = goBundle();
        this.registry.register("go", bundle1);
        this.registry.register("go", bundle2);

        assertThat("Overwritten bundle should be the latest",
            this.registry.get("go").get().parser(), is(bundle2.parser()));
    }

    @Test
    void bundleComponentsAreAccessible() {
        final CooldownAdapterBundle<List<String>> bundle = goBundle();
        this.registry.register("go", bundle);

        final CooldownAdapterBundle<?> found = this.registry.get("go").get();
        assertThat("parser is not null", found.parser(), is(notNullValue()));
        assertThat("filter is not null", found.filter(), is(notNullValue()));
        assertThat("rewriter is not null", found.rewriter(), is(notNullValue()));
        assertThat("detector is not null", found.detector(), is(notNullValue()));
        assertThat("responseFactory is not null", found.responseFactory(), is(notNullValue()));
    }

    @Test
    void bundleRejectsNullComponents() {
        try {
            new CooldownAdapterBundle<>(null, new StubFilter(), new StubRewriter(),
                new StubDetector(), new StubResponseFactory());
            assertThat("Should have thrown NPE", false, is(true));
        } catch (final NullPointerException expected) {
            // expected
        }
    }

    // --- Helper: create a Go-style bundle ---

    private static CooldownAdapterBundle<List<String>> goBundle() {
        return new CooldownAdapterBundle<>(
            new StubParser(),
            new StubFilter(),
            new StubRewriter(),
            new StubDetector(),
            new StubResponseFactory()
        );
    }

    // --- Stub implementations ---

    private static final class StubParser implements MetadataParser<List<String>> {
        @Override
        public List<String> parse(final byte[] bytes) {
            final String body = new String(bytes, StandardCharsets.UTF_8);
            final List<String> versions = new ArrayList<>();
            for (final String line : body.split("\n")) {
                final String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    versions.add(trimmed);
                }
            }
            return versions;
        }

        @Override
        public List<String> extractVersions(final List<String> metadata) {
            return metadata;
        }

        @Override
        public Optional<String> getLatestVersion(final List<String> metadata) {
            return metadata.isEmpty() ? Optional.empty()
                : Optional.of(metadata.get(metadata.size() - 1));
        }

        @Override
        public String contentType() {
            return "text/plain";
        }
    }

    private static final class StubFilter implements MetadataFilter<List<String>> {
        @Override
        public List<String> filter(final List<String> metadata, final Set<String> blocked) {
            final List<String> result = new ArrayList<>(metadata);
            result.removeAll(blocked);
            return result;
        }

        @Override
        public List<String> updateLatest(final List<String> metadata, final String newLatest) {
            return metadata;
        }
    }

    private static final class StubRewriter implements MetadataRewriter<List<String>> {
        @Override
        public byte[] rewrite(final List<String> metadata) {
            return String.join("\n", metadata).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String contentType() {
            return "text/plain";
        }
    }

    private static final class StubDetector implements MetadataRequestDetector {
        @Override
        public boolean isMetadataRequest(final String path) {
            return path != null && path.endsWith("/@v/list");
        }

        @Override
        public Optional<String> extractPackageName(final String path) {
            if (!this.isMetadataRequest(path)) {
                return Optional.empty();
            }
            return Optional.of(path.substring(1, path.length() - "/@v/list".length()));
        }

        @Override
        public String repoType() {
            return "go";
        }
    }

    private static final class StubResponseFactory implements CooldownResponseFactory {
        @Override
        public Response forbidden(final CooldownBlock block) {
            return ResponseBuilder.forbidden()
                .textBody("blocked by cooldown")
                .build();
        }

        @Override
        public String repoType() {
            return "go";
        }
    }
}
