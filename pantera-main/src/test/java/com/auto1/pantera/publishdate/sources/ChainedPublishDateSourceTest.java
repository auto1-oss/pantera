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
package com.auto1.pantera.publishdate.sources;

import com.auto1.pantera.publishdate.PublishDateSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChainedPublishDateSourceTest {

    private static final Instant MAVEN_DATE = Instant.parse("2024-03-15T07:28:40Z");
    private static final Instant JFROG_DATE = Instant.parse("2024-06-30T07:19:16Z");

    @FunctionalInterface
    interface FetchFn {
        CompletableFuture<Optional<Instant>> apply(String name, String version);
    }

    private static PublishDateSource stub(
        final String repoType,
        final String sourceId,
        final FetchFn fn
    ) {
        return new PublishDateSource() {
            @Override
            public String repoType() {
                return repoType;
            }

            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public CompletableFuture<Optional<Instant>> fetch(
                final String name, final String version
            ) {
                return fn.apply(name, version);
            }
        };
    }

    @Test
    void returnsPrimaryWhenPresent() throws Exception {
        final AtomicInteger fallbackCalls = new AtomicInteger(0);
        final PublishDateSource primary = stub(
            "maven", "primary_id",
            (n, v) -> CompletableFuture.completedFuture(Optional.of(MAVEN_DATE))
        );
        final PublishDateSource fallback = stub(
            "maven", "fallback_id",
            (n, v) -> {
                fallbackCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(JFROG_DATE));
            }
        );
        final ChainedPublishDateSource chained = new ChainedPublishDateSource(primary, fallback);
        final Optional<Instant> result = chained.fetch("com.example.artifact", "1.0").get();
        assertEquals(Optional.of(MAVEN_DATE), result);
        assertEquals(0, fallbackCalls.get(), "Fallback must NOT be called when primary succeeds");
    }

    @Test
    void fallsBackWhenPrimaryEmpty() throws Exception {
        final PublishDateSource primary = stub(
            "maven", "primary_id",
            (n, v) -> CompletableFuture.completedFuture(Optional.empty())
        );
        final PublishDateSource fallback = stub(
            "maven", "fallback_id",
            (n, v) -> CompletableFuture.completedFuture(Optional.of(JFROG_DATE))
        );
        final ChainedPublishDateSource chained = new ChainedPublishDateSource(primary, fallback);
        final Optional<Instant> result = chained.fetch("com.example.artifact", "1.0").get();
        assertEquals(Optional.of(JFROG_DATE), result);
    }

    @Test
    void returnsEmptyWhenBothEmpty() throws Exception {
        final PublishDateSource primary = stub(
            "maven", "primary_id",
            (n, v) -> CompletableFuture.completedFuture(Optional.empty())
        );
        final PublishDateSource fallback = stub(
            "maven", "fallback_id",
            (n, v) -> CompletableFuture.completedFuture(Optional.empty())
        );
        final ChainedPublishDateSource chained = new ChainedPublishDateSource(primary, fallback);
        final Optional<Instant> result = chained.fetch("com.example.artifact", "1.0").get();
        assertTrue(result.isEmpty(), "Expected Optional.empty() when both sources return empty");
    }

    @Test
    void fallsBackWhenPrimaryFails() throws Exception {
        final PublishDateSource primary = stub(
            "maven", "primary_id",
            (n, v) -> {
                final CompletableFuture<Optional<Instant>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("primary failure"));
                return failed;
            }
        );
        final PublishDateSource fallback = stub(
            "maven", "fallback_id",
            (n, v) -> CompletableFuture.completedFuture(Optional.of(JFROG_DATE))
        );
        final ChainedPublishDateSource chained = new ChainedPublishDateSource(primary, fallback);
        final Optional<Instant> result = chained.fetch("com.example.artifact", "1.0").get();
        assertEquals(Optional.of(JFROG_DATE), result);
    }

    @Test
    void propagatesExceptionWhenBothFail() {
        final PublishDateSource primary = stub(
            "maven", "primary_id",
            (n, v) -> {
                final CompletableFuture<Optional<Instant>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("primary failure"));
                return failed;
            }
        );
        final PublishDateSource fallback = stub(
            "maven", "fallback_id",
            (n, v) -> {
                final CompletableFuture<Optional<Instant>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("fallback failure"));
                return failed;
            }
        );
        final ChainedPublishDateSource chained = new ChainedPublishDateSource(primary, fallback);
        assertThrows(Exception.class, () -> chained.fetch("com.example.artifact", "1.0").get());
    }

    @Test
    void repoTypeAndSourceIdFromPrimary() {
        final PublishDateSource primary = stub(
            "maven", "primary_id",
            (n, v) -> CompletableFuture.completedFuture(Optional.empty())
        );
        final PublishDateSource fallback = stub(
            "maven", "fallback_id",
            (n, v) -> CompletableFuture.completedFuture(Optional.empty())
        );
        final ChainedPublishDateSource chained = new ChainedPublishDateSource(primary, fallback);
        assertEquals("maven", chained.repoType());
        assertEquals("primary_id+fallback_id", chained.sourceId());
    }
}
