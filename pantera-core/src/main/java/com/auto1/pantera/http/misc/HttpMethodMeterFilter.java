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
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Meter filter that keeps the {@code method} tag of the HTTP server metrics
 * to the methods HTTP defines.
 *
 * <p>The server metrics are tagged with the method of each request, and a
 * method is whatever token the client sends: a request needs no credentials
 * to be counted. Without this filter every made-up method token creates a
 * new set of time series (30 per token with latency buckets on), so a client
 * could grow the registry until the heap is gone. Any other token is
 * recorded as {@code _other}.</p>
 *
 * @since 2.2.12
 */
public final class HttpMethodMeterFilter implements MeterFilter {

    /**
     * Tag name to filter.
     */
    private static final String TAG_NAME = "method";

    /**
     * Value recorded for any other token.
     */
    private static final String OTHER = "_other";

    /**
     * The methods HTTP defines.
     */
    private static final Set<String> METHODS = Set.of(
        "GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE"
    );

    @Override
    public Meter.Id map(final Meter.Id id) {
        final String method = id.getTag(HttpMethodMeterFilter.TAG_NAME);
        final Meter.Id mapped;
        if (method == null || HttpMethodMeterFilter.METHODS.contains(method)) {
            mapped = id;
        } else {
            final List<Tag> tags = id.getTags().stream()
                .map(
                    tag -> HttpMethodMeterFilter.TAG_NAME.equals(tag.getKey())
                        ? Tag.of(HttpMethodMeterFilter.TAG_NAME, HttpMethodMeterFilter.OTHER)
                        : tag
                )
                .collect(Collectors.toList());
            mapped = id.replaceTags(tags);
        }
        return mapped;
    }
}
