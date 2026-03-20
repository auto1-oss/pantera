/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.misc;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Meter filter that caps the cardinality of the "repo_name" tag.
 * Only the first N distinct repo names are kept; additional repos are
 * replaced with "_other" to prevent unbounded series growth.
 *
 * @since 1.20.13
 */
public final class RepoNameMeterFilter implements MeterFilter {

    /**
     * Tag name to filter.
     */
    private static final String TAG_NAME = "repo_name";

    /**
     * Maximum number of distinct repo_name values.
     */
    private final int maxRepos;

    /**
     * Known repo names (first N to be seen).
     */
    private final Set<String> known;

    /**
     * Counter for tracking how many distinct repos we've seen.
     */
    private final AtomicInteger count;

    /**
     * Constructor.
     * @param maxRepos Maximum distinct repo names to track
     */
    public RepoNameMeterFilter(final int maxRepos) {
        this.maxRepos = maxRepos;
        this.known = ConcurrentHashMap.newKeySet();
        this.count = new AtomicInteger(0);
    }

    @Override
    public Meter.Id map(final Meter.Id id) {
        final String repoName = id.getTag(TAG_NAME);
        if (repoName == null) {
            return id;
        }
        if (this.known.contains(repoName)) {
            return id;
        }
        if (this.count.get() < this.maxRepos) {
            if (this.known.add(repoName)) {
                this.count.incrementAndGet();
            }
            return id;
        }
        // Over limit — replace tag with _other
        final List<Tag> newTags = id.getTags().stream()
            .map(tag -> TAG_NAME.equals(tag.getKey())
                ? Tag.of(TAG_NAME, "_other")
                : tag)
            .collect(Collectors.toList());
        return id.replaceTags(newTags);
    }
}
