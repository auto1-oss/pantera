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
package com.auto1.pantera.publishdate;

import com.auto1.pantera.http.Headers;

import java.time.Instant;
import java.util.Optional;

/**
 * Track 5 Phase 3B SPI: extract the canonical publish date for a freshly
 * fetched artifact from the upstream response. Each adapter registers one
 * implementation per repo-type at boot via
 * {@link PublishDateExtractors#register(String, PublishDateExtractor)}.
 *
 * <p>Why an SPI: pre-Track-5 every adapter scraped its own subset of headers
 * (Maven: {@code Last-Modified}; npm: {@code time.{version}}; pypi:
 * {@code upload-time}) inside adapter-specific helpers — when the
 * stream-through path or an admin tool needed the publish date, the
 * extraction lived inside the slice and was invisible to shared code paths.
 * The {@code Headers.EMPTY} regression that Track 4 quietly introduced is a
 * direct symptom of that scattering. Centralising the extractor under a
 * registry keyed by repo-type lets the cache-write path call one method and
 * trust that every adapter has provided the correct shape.
 *
 * <p>Contract:
 * <ul>
 *   <li>Pure function: same input → same output, no I/O.</li>
 *   <li>Returns {@link Optional#empty()} when the upstream omitted the
 *       relevant header — caller decides whether to fall back to
 *       {@code System.currentTimeMillis()} or leave the date unset.</li>
 *   <li>Implementations MUST be thread-safe.</li>
 * </ul>
 *
 * @since 2.2.0
 */
@FunctionalInterface
public interface PublishDateExtractor {

    /**
     * Extract the upstream publish timestamp.
     *
     * @param upstreamHeaders Headers returned by the upstream registry.
     * @param name            Artifact name (per-repo-type convention).
     * @param version         Artifact version.
     * @return Publish timestamp when discoverable; {@link Optional#empty()}
     *         otherwise. Implementations MUST NOT throw — parse failures
     *         resolve to empty so an unparseable header degrades gracefully
     *         (a missing publish_date is harmless; a thrown exception in
     *         the cache-write event path is not).
     */
    Optional<Instant> extract(Headers upstreamHeaders, String name, String version);
}
